package io.github.vzd3v.xwiki.docling;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversionContractTest {
  @TempDir Path directory;

  private Config config(long max) {
    return new Config(
        directory,
        URI.create("http://localhost:5001"),
        null,
        "test",
        List.of("en"),
        "easyocr",
        Duration.ofSeconds(2),
        1,
        2,
        20,
        10,
        max,
        1000,
        32,
        1000000,
        null);
  }

  @Test
  void identicalBytesConvertOnceAcrossRequestsAndRestart() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    String key;
    try (ConversionService service =
        new ConversionService(
            config(1000),
            (file, mime) -> {
              calls.incrementAndGet();
              return "recognized";
            },
            (page, force) -> {})) {
      try (var p =
          service.prepare(
              new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)),
              "application/pdf")) {
        key = p.key();
        assertNull(p.cached());
      }
      service.start();
      await(() -> service.status().getOrDefault("ready", 0L) == 1);
      try (var p =
          service.prepare(
              new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)),
              "application/pdf")) {
        assertEquals("recognized", p.cached());
        assertEquals(key, p.key());
      }
      assertEquals(1, calls.get());
    }
    try (ConversionService service =
        new ConversionService(
            config(1000),
            (file, mime) -> {
              calls.incrementAndGet();
              return "wrong";
            },
            (page, force) -> {})) {
      service.start();
      try (var p =
          service.prepare(
              new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)),
              "application/pdf")) {
        assertEquals("recognized", p.cached());
      }
      assertEquals(1, calls.get());
    }
  }

  @Test
  void oversizedInputIsReplayableWithoutSchedulingRemoteWork() throws Exception {
    byte[] bytes = "too large for configured limit".getBytes(StandardCharsets.UTF_8);
    try (ConversionService service =
            new ConversionService(
                config(3),
                (file, mime) -> {
                  fail("must not convert");
                  return "";
                },
                (page, force) -> {});
        var p = service.prepare(new ByteArrayInputStream(bytes), "application/pdf")) {
      assertFalse(p.complete());
      assertNull(p.key());
      try (var replay = p.nativeInput()) {
        assertArrayEquals(bytes, replay.readAllBytes());
      }
      assertEquals(0L, service.status().getOrDefault("pending", 0L));
    }
  }

  @Test
  void transientConversionAndIndexFailuresRetryDurably() throws Exception {
    AtomicInteger conversions = new AtomicInteger();
    AtomicInteger indexes = new AtomicInteger();
    try (ConversionService service =
        new ConversionService(
            config(1000),
            (file, mime) -> {
              if (conversions.incrementAndGet() == 1) throw new java.io.IOException("temporary");
              return "ok";
            },
            (page, force) -> {
              if (indexes.incrementAndGet() == 1) throw new java.io.IOException("temporary");
            })) {
      service.reconcile("wiki:Page");
      try (var p = service.prepare(new ByteArrayInputStream(new byte[] {1}), "application/pdf")) {}
      service.start();
      await(
          () ->
              service.status().getOrDefault("ready", 0L) == 1
                  && service.status().getOrDefault("pendingIndex", 0L) == 0);
      assertEquals(2, conversions.get());
      assertEquals(2, indexes.get());
    }
  }

  private interface Condition {
    boolean get() throws Exception;
  }

  @Test
  void queueCapacityBackpressurePreservesInputAndFailedSpoolsCanBeForgotten() throws Exception {
    Config bounded =
        new Config(
            directory,
            URI.create("http://localhost:5001"),
            null,
            "test",
            List.of("en"),
            "easyocr",
            Duration.ofSeconds(2),
            1,
            1,
            20,
            10,
            8,
            1000,
            1,
            8,
            null);
    try (ConversionService service =
        new ConversionService(
            bounded,
            (file, mime) -> {
              throw new java.io.IOException("bad file");
            },
            (page, force) -> {})) {
      String first;
      try (var p =
          service.prepare(new ByteArrayInputStream(new byte[] {1, 2, 3}), "application/pdf")) {
        first = p.key();
        assertNotNull(first);
      }
      try (var p =
          service.prepare(new ByteArrayInputStream(new byte[] {4, 5, 6}), "application/pdf")) {
        assertTrue(p.deferred());
        assertNull(p.key());
        try (var replay = p.nativeInput()) {
          assertArrayEquals(new byte[] {4, 5, 6}, replay.readAllBytes());
        }
      }
      service.replaceOwners(
          "wiki:Page", List.of(new Store.Owner("wiki:Page@file", "wiki:Page", "1.1", first)));
      service.start();
      await(() -> service.status().getOrDefault("failed", 0L) == 1);
      assertEquals(1, service.forgetFailed());
      assertTrue(service.owners("wiki:Page").isEmpty());
      try (var p =
          service.prepare(new ByteArrayInputStream(new byte[] {4, 5, 6}), "application/pdf")) {
        assertFalse(p.deferred());
        assertNotNull(p.key());
      }
    }
  }

  private static void await(Condition condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.get()) {
      if (System.nanoTime() > end) fail("Background work did not complete");
      Thread.sleep(10);
    }
  }

  @Test
  void borrowedFileCacheHitsAvoidTemporaryCopiesAndPreserveLimitAndCapacityFallback()
      throws Exception {
    Path borrowed = directory.resolve("borrowed.pdf");
    byte[] original = new byte[] {1, 2, 3};
    Files.write(borrowed, original);
    AtomicInteger calls = new AtomicInteger();
    Config c = config(8);
    try (ConversionService service =
        new ConversionService(
            c,
            (file, mime) -> {
              calls.incrementAndGet();
              return "recognized";
            },
            (page, force) -> {})) {
      assertNull(service.cachedOrEnqueue(borrowed, "application/pdf"));
      service.start();
      await(() -> service.status().getOrDefault("ready", 0L) == 1);
      try (var watcher = directory.getFileSystem().newWatchService()) {
        directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        assertEquals("recognized", service.cachedOrEnqueue(borrowed, "application/pdf"));
        var events = watcher.poll();
        if (events != null)
          assertTrue(
              events.pollEvents().stream()
                  .noneMatch(event -> event.context().toString().startsWith("incoming-")));
      }
      assertArrayEquals(original, Files.readAllBytes(borrowed));
      Files.write(borrowed, new byte[9]);
      assertNull(service.cachedOrEnqueue(borrowed, "application/pdf"));
      assertEquals(9, Files.size(borrowed));
      assertEquals(1, calls.get());
    }
    Config bounded =
        new Config(
            directory.resolve("bounded"),
            c.endpoint(),
            null,
            "test",
            c.languages(),
            c.ocrEngine(),
            c.timeout(),
            1,
            1,
            20,
            10,
            8,
            1000,
            1,
            8,
            null);
    Files.write(borrowed, original);
    try (ConversionService service =
        new ConversionService(bounded, (file, mime) -> "unused", (page, force) -> {})) {
      assertNull(service.cachedOrEnqueue(borrowed, "application/pdf"));
      Files.write(borrowed, new byte[] {4, 5});
      assertNull(service.cachedOrEnqueue(borrowed, "application/pdf"));
      assertEquals(1L, service.status().get("pending"));
      assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(borrowed));
    }
  }

  @Test
  void exactCleanupRejectsUnfinishedJobsAndRemovesOnlyRequestedUnusedData() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ConversionService service =
        new ConversionService(
            config(1000),
            (file, mime) -> {
              byte value = Files.readAllBytes(file)[0];
              if (value == 2) {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timeout");
              }
              if (value == 3) throw new ConversionFailure("invalid_document", false);
              return "recognized";
            },
            (page, force) -> {})) {
      String ready;
      try (var p = service.prepare(new ByteArrayInputStream(new byte[] {1}), "application/pdf")) {
        ready = p.key();
      }
      service.start();
      await(() -> service.status().getOrDefault("ready", 0L) == 1);
      String running;
      try (var p = service.prepare(new ByteArrayInputStream(new byte[] {2}), "application/pdf")) {
        running = p.key();
      }
      assertTrue(started.await(5, TimeUnit.SECONDS));
      String pending;
      try (var p = service.prepare(new ByteArrayInputStream(new byte[] {3}), "application/pdf")) {
        pending = p.key();
      }
      try {
        assertThrows(
            IllegalStateException.class, () -> service.forgetUnused(List.of(ready, running)));
        assertThrows(IllegalStateException.class, () -> service.forgetUnused(List.of(pending)));
        assertEquals(1L, service.status().get("ready"));
        assertThrows(
            IllegalArgumentException.class, () -> service.forgetUnused(List.of("../outside")));
        assertThrows(IllegalArgumentException.class, () -> service.forgetUnused(List.of()));
        assertEquals(1, service.forgetUnused(List.of(ready, ready)));
        assertEquals(0, service.forgetUnused(List.of(ready)));
      } finally {
        release.countDown();
      }
      await(() -> service.status().getOrDefault("failed", 0L) == 1);
      assertTrue(Files.exists(directory.resolve("inputs").resolve(pending)));
      assertEquals(1, service.forgetUnused(List.of(pending)));
      assertFalse(Files.exists(directory.resolve("inputs").resolve(pending)));
      assertEquals(1L, service.status().get("ready"));
    }
  }

  @Test
  void oldCompletionCannotDeleteAnInputReadmittedAfterTargetedCleanup() throws Exception {
    CountDownLatch deleting = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean pauseOnce = new AtomicBoolean();
    AtomicInteger conversions = new AtomicInteger();
    var executor = Executors.newSingleThreadExecutor();
    byte[] original = new byte[] {7, 8, 9};
    try (ConversionService service =
        new ConversionService(
            config(1000),
            (file, mime) -> {
              assertArrayEquals(original, Files.readAllBytes(file));
              conversions.incrementAndGet();
              return "recognized";
            },
            (page, force) -> {},
            file -> {
              if (pauseOnce.compareAndSet(false, true)) {
                deleting.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timeout");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(e);
                }
              }
              Files.deleteIfExists(file);
            })) {
      String key;
      try (var p = service.prepare(new ByteArrayInputStream(original), "application/pdf")) {
        key = p.key();
      }
      service.start();
      assertTrue(deleting.await(5, TimeUnit.SECONDS));
      var replacement =
          executor.submit(
              () -> {
                assertEquals(1, service.forgetUnused(List.of(key)));
                try (var p =
                    service.prepare(new ByteArrayInputStream(original), "application/pdf")) {
                  return p.key();
                }
              });
      try {
        assertThrows(
            TimeoutException.class,
            () -> replacement.get(100, TimeUnit.MILLISECONDS),
            "forget/re-admission must wait until old input cleanup completes");
      } finally {
        release.countDown();
      }
      assertEquals(key, replacement.get(5, TimeUnit.SECONDS));
      await(() -> conversions.get() == 2 && service.status().getOrDefault("ready", 0L) == 1);
      assertEquals(0L, service.status().getOrDefault("failed", 0L));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }
}

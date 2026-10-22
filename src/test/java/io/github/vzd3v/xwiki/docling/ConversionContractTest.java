package io.github.vzd3v.xwiki.docling;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        1000000);
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
            8);
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
}

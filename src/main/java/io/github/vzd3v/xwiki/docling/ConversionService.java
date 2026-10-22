package io.github.vzd3v.xwiki.docling;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Durable background conversion and indexing. Parsing never performs remote conversion. */
public final class ConversionService implements AutoCloseable {
  interface Indexer {
    void index(String page, boolean needsIndex) throws Exception;
  }

  public record Prepared(
      Path prefix,
      InputStream remaining,
      String key,
      String cached,
      boolean complete,
      boolean deferred)
      implements AutoCloseable {
    public InputStream nativeInput() throws IOException {
      return new SequenceInputStream(Files.newInputStream(prefix), remaining);
    }

    @Override
    public void close() throws IOException {
      Files.deleteIfExists(prefix);
    }
  }

  private static String fileName(Path path) {
    return java.util.Objects.requireNonNull(path.getFileName(), "Expected a named child file")
        .toString();
  }

  private static final Logger LOG = LoggerFactory.getLogger(ConversionService.class);
  private final Config config;
  private final Store store;
  private final Converter converter;
  private final Indexer indexer;
  private final Path inputs;
  private final ScheduledExecutorService executor;
  private final String profile;
  private volatile boolean closed;

  ConversionService(Config config, Converter converter, Indexer indexer) throws Exception {
    this.config = config;
    this.converter = converter;
    this.indexer = indexer;
    inputs = config.dataDir().resolve("inputs");
    Files.createDirectories(inputs);
    try {
      Files.setPosixFilePermissions(
          config.dataDir(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      Files.setPosixFilePermissions(
          inputs, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      /* Non-POSIX filesystem: operator ACL applies. */
    }
    store = new Store(config.dataDir());
    profile = Hashes.of(config.profile());
    try {
      store.failInactiveProfiles(profile);
      cleanupInterruptedSpools();
      executor =
          Executors.newScheduledThreadPool(
              config.workers() + 1,
              r -> {
                Thread t = new Thread(r, "xwiki-docling-worker");
                t.setDaemon(true);
                t.setContextClassLoader(ConversionService.class.getClassLoader());
                return t;
              });
    } catch (Exception e) {
      store.close();
      throw e;
    }
  }

  private void cleanupInterruptedSpools() throws IOException, SQLException {
    // The runtime lock is held: no live parser or worker can own these partial copies.
    try (var files = Files.list(config.dataDir())) {
      for (Path file :
          files
              .filter(p -> fileName(p).startsWith("incoming-") && fileName(p).endsWith(".tmp"))
              .toList()) Files.deleteIfExists(file);
    }
    try (var files = Files.list(inputs)) {
      for (Path file : files.toList()) {
        String name = fileName(file);
        if (name.startsWith("pending-") && name.endsWith(".tmp")) Files.deleteIfExists(file);
        else if (name.matches("[0-9a-f]{64}")
            && (!store.contains(name) || store.ready(name) != null)) Files.deleteIfExists(file);
      }
    }
  }

  void start() {
    for (int i = 0; i < config.workers(); i++)
      executor.scheduleWithFixedDelay(
          this::convertOne, 0, config.pollMillis(), TimeUnit.MILLISECONDS);
    executor.scheduleWithFixedDelay(this::indexOne, 0, config.pollMillis(), TimeUnit.MILLISECONDS);
  }

  public Prepared prepare(InputStream input, String mime) throws IOException, SQLException {
    if (closed) throw new IOException("Docling runtime is closed");
    Path temp = Files.createTempFile(config.dataDir(), "incoming-", ".tmp");
    MessageDigest hash = Hashes.digest();
    long bytes = 0;
    boolean complete = false;
    try {
      try (var out = Files.newOutputStream(temp)) {
        byte[] buffer = new byte[32768];
        while (bytes <= config.maxInputBytes()) {
          int count =
              input.read(
                  buffer, 0, (int) Math.min(buffer.length, config.maxInputBytes() + 1 - bytes));
          if (count < 0) {
            complete = true;
            break;
          }
          out.write(buffer, 0, count);
          hash.update(buffer, 0, count);
          bytes += count;
        }
      }
      if (!complete) return new Prepared(temp, input, null, null, false, false);
      String content = Hashes.hex(hash.digest());
      String key = Hashes.of(content + "\n" + profile + "\n" + mime);
      String ready = store.ready(key);
      if (ready != null) return new Prepared(temp, input, key, ready, true, false);
      Path destination = inputs.resolve(key);
      if (!admit(temp, destination, key, content, mime, bytes))
        return new Prepared(temp, input, null, null, false, true);
      return new Prepared(temp, input, key, store.ready(key), true, false);
    } catch (IOException | SQLException | RuntimeException e) {
      Files.deleteIfExists(temp);
      throw e;
    }
  }

  boolean replaceOwners(String page, List<Store.Owner> owners) throws SQLException {
    return store.replaceOwners(
        page,
        owners.stream().sorted(java.util.Comparator.comparing(Store.Owner::attachment)).toList());
  }

  private synchronized boolean admit(
      Path temp, Path destination, String key, String content, String mime, long bytes)
      throws IOException, SQLException {
    if (store.ready(key) != null) return true;
    if (!Files.exists(destination)) {
      long size = 0;
      int count = 0;
      try (var files = Files.list(inputs)) {
        for (Path file : files.filter(p -> fileName(p).matches("[0-9a-f]{64}")).toList()) {
          try {
            size += Files.size(file);
            count++;
          } catch (java.nio.file.NoSuchFileException ignored) {
            /* A completed worker freed capacity. */
          }
        }
      }
      if (count >= config.maxPendingJobs() || bytes > config.maxSpoolBytes() - size) return false;
      Path staging = Files.createTempFile(inputs, "pending-", ".tmp");
      try {
        Files.copy(temp, staging, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel channel =
            FileChannel.open(staging, java.nio.file.StandardOpenOption.WRITE)) {
          channel.force(true);
        }
        Files.move(
            staging,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(staging);
      }
    }
    store.enqueue(key, content, profile, mime, destination.toString());
    return true;
  }

  List<String> pages() throws SQLException {
    return store.pages();
  }

  List<Store.Owner> owners(String page) throws SQLException {
    return store.owners(page);
  }

  boolean currentProfile(String key) throws SQLException {
    return store.currentProfile(key, profile);
  }

  public Map<String, Long> status() throws SQLException {
    return store.stats();
  }

  public List<Map<String, Object>> failures(int limit) throws SQLException {
    return store.failures(limit);
  }

  public void reconcile(String page) throws SQLException {
    store.requestReconcile(page);
  }

  public void retryFailed() throws SQLException {
    store.retryFailed(profile);
  }

  public synchronized int forgetFailed() throws SQLException, IOException {
    List<String> keys = store.forgetFailed();
    for (String key : keys) Files.deleteIfExists(inputs.resolve(key));
    return keys.size();
  }

  private void convertOne() {
    if (closed) return;
    Store.Job job = null;
    try {
      job = store.claim(System.currentTimeMillis(), config.maxAttempts(), profile);
      if (job == null) return;
      long start = System.nanoTime();
      String text = converter.convert(Path.of(job.file()), job.mime());
      if (text.length() > config.maxOutputChars())
        throw new IOException("Conversion output exceeds configured limit");
      store.completed(job, text);
      try {
        Files.deleteIfExists(Path.of(job.file()));
      } catch (IOException cleanup) {
        LOG.warn(
            "component=xwiki-docling event=input_cleanup_failed job={} errorType={}",
            job.key(),
            cleanup.getClass().getName());
      }
      LOG.info(
          "component=xwiki-docling event=converted job={} attempt={} durationMs={} chars={}",
          job.key(),
          job.attempts(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
          text.length());
    } catch (Exception e) {
      String code = e instanceof ConversionFailure ? e.getMessage() : e.getClass().getSimpleName();
      boolean retryable = !(e instanceof ConversionFailure failure) || failure.retryable();
      LOG.warn(
          "component=xwiki-docling event=conversion_failed job={} errorCode={} retryable={} causeTypes={}",
          job == null ? "none" : job.key(),
          code,
          retryable,
          causeTypes(e));
      if (job != null)
        try {
          store.failed(
              job,
              code,
              System.currentTimeMillis() + backoff(job.attempts()),
              retryable ? config.maxAttempts() : job.attempts());
        } catch (SQLException sql) {
          LOG.error("component=xwiki-docling event=queue_failure", sql);
        }
    }
  }

  private long backoff(int attempt) {
    return Math.min(
        TimeUnit.HOURS.toMillis(1),
        config.retryMillis() * (1L << Math.min(10, Math.max(0, attempt - 1))));
  }

  private static String causeTypes(Throwable failure) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; failure != null && i < 8; i++, failure = failure.getCause()) {
      if (i > 0) result.append("->");
      result.append(failure.getClass().getSimpleName());
    }
    return result.toString();
  }

  private void indexOne() {
    if (closed) return;
    Store.IndexJob job = null;
    try {
      job = store.indexJob(System.currentTimeMillis());
      if (job == null) return;
      indexer.index(job.page(), job.needsIndex());
      store.indexed(job);
      LOG.info("component=xwiki-docling event=reconciled pageHash={}", Hashes.of(job.page()));
    } catch (Exception e) {
      LOG.warn("component=xwiki-docling event=index_failed causeTypes={}", causeTypes(e));
      if (job != null)
        try {
          store.indexFailed(
              job, causeTypes(e), System.currentTimeMillis() + backoff(job.attempts() + 1));
        } catch (SQLException sql) {
          LOG.error("component=xwiki-docling event=outbox_failure", sql);
        }
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(
          Math.min(10, config.timeout().toSeconds() + 5), TimeUnit.SECONDS))
        throw new IOException(
            "Docling workers did not terminate; retain runtime lock until process exits");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while stopping Docling workers; retain runtime lock", e);
    }
    store.close();
  }
}

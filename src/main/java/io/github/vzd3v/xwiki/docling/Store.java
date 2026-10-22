package io.github.vzd3v.xwiki.docling;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One runtime owns this private durable store. All cross-table transitions are atomic. */
final class Store implements AutoCloseable {
  record Job(String key, String file, String mime, int attempts) {}

  record Owner(String attachment, String page, String version, String key) {}

  record IndexJob(String page, long generation, int attempts, boolean needsIndex) {}

  private final String url;
  private final FileChannel lockChannel;
  private final FileLock lock;

  Store(Path directory) throws IOException, SQLException {
    Files.createDirectories(directory);
    lockChannel =
        FileChannel.open(
            directory.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      lock = lockChannel.tryLock();
    } catch (IOException | RuntimeException e) {
      lockChannel.close();
      throw e;
    }
    if (lock == null) {
      lockChannel.close();
      throw new IOException("Another runtime owns the Docling data directory");
    }
    url = "jdbc:sqlite:" + directory.resolve("cache.sqlite");
    try {
      Class.forName("org.sqlite.JDBC");
      try (Connection c = connection();
          var s = c.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        String schema;
        try (var schemaInput = Store.class.getResourceAsStream("/db/ddl.sql")) {
          if (schemaInput == null) throw new IOException("Missing embedded schema");
          schema = new String(schemaInput.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String statement : schema.split(";")) if (!statement.isBlank()) s.execute(statement);
        try (ResultSet r = s.executeQuery("SELECT version FROM schema_version")) {
          if (!r.next() || r.getInt(1) != 1) throw new SQLException("Unsupported schema version");
        }
        s.executeUpdate(
            "UPDATE cache SET state='pending',attempts=MAX(0,attempts-1) WHERE state='running'");
      }
    } catch (IOException | SQLException | ClassNotFoundException e) {
      close();
      if (e instanceof SQLException sql) throw sql;
      throw new IOException("Cannot initialize Docling store", e);
    }
  }

  private Connection connection() throws SQLException {
    Connection c = DriverManager.getConnection(url);
    try (var s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=10000");
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  synchronized String ready(String key) throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "SELECT output_text FROM cache WHERE cache_key=? AND state='ready'")) {
      p.setString(1, key);
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }

  synchronized boolean currentProfile(String key, String profile) throws SQLException {
    try (Connection c = connection();
        var p = c.prepareStatement("SELECT 1 FROM cache WHERE cache_key=? AND profile_hash=?")) {
      p.setString(1, key);
      p.setString(2, profile);
      try (ResultSet r = p.executeQuery()) {
        return r.next();
      }
    }
  }

  synchronized boolean contains(String key) throws SQLException {
    try (Connection c = connection();
        var p = c.prepareStatement("SELECT 1 FROM cache WHERE cache_key=?")) {
      p.setString(1, key);
      try (ResultSet r = p.executeQuery()) {
        return r.next();
      }
    }
  }

  synchronized void failInactiveProfiles(String profile) throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "UPDATE cache SET state='failed',last_error='inactive_profile' WHERE state='pending' AND profile_hash<>?")) {
      p.setString(1, profile);
      p.executeUpdate();
    }
  }

  synchronized void enqueue(String key, String content, String profile, String mime, String file)
      throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "INSERT OR IGNORE INTO cache(cache_key,content_hash,profile_hash,mime,input_file,state,created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?)")) {
      p.setString(1, key);
      p.setString(2, content);
      p.setString(3, profile);
      p.setString(4, mime);
      p.setString(5, file);
      p.setLong(6, System.currentTimeMillis());
      p.setLong(7, System.currentTimeMillis());
      p.executeUpdate();
    }
  }

  synchronized Job claim(long now, int maxAttempts, String profile) throws SQLException {
    try (Connection c = connection()) {
      c.setAutoCommit(false);
      try (var p =
          c.prepareStatement(
              "SELECT cache_key,input_file,mime,attempts FROM cache WHERE state='pending' AND available_at<=? AND attempts<? AND profile_hash=? ORDER BY created_at LIMIT 1")) {
        p.setLong(1, now);
        p.setInt(2, maxAttempts);
        p.setString(3, profile);
        Job job;
        try (ResultSet r = p.executeQuery()) {
          if (!r.next()) {
            c.commit();
            return null;
          }
          job = new Job(r.getString(1), r.getString(2), r.getString(3), r.getInt(4) + 1);
        }
        try (var u =
            c.prepareStatement(
                "UPDATE cache SET state='running',attempts=attempts+1,updated_at=? WHERE cache_key=?")) {
          u.setLong(1, now);
          u.setString(2, job.key());
          u.executeUpdate();
        }
        c.commit();
        return job;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  synchronized void completed(Job job, String text) throws SQLException {
    try (Connection c = connection()) {
      c.setAutoCommit(false);
      try (var p =
          c.prepareStatement(
              "UPDATE cache SET state='ready',output_text=?,last_error=NULL,updated_at=? WHERE cache_key=?")) {
        p.setString(1, text);
        p.setLong(2, System.currentTimeMillis());
        p.setString(3, job.key());
        p.executeUpdate();
        try (var q =
            c.prepareStatement(
                "INSERT INTO reindex(page_ref,needs_index) SELECT DISTINCT page_ref,1 FROM owners WHERE cache_key=? ON CONFLICT(page_ref) DO UPDATE SET generation=generation+1,attempts=0,available_at=0,last_error=NULL,needs_index=1")) {
          q.setString(1, job.key());
          q.executeUpdate();
        }
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  synchronized void failed(Job job, String error, long retryAt, int maxAttempts)
      throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "UPDATE cache SET state=?,last_error=?,available_at=?,updated_at=? WHERE cache_key=?")) {
      p.setString(1, job.attempts() >= maxAttempts ? "failed" : "pending");
      p.setString(2, error);
      p.setLong(3, retryAt);
      p.setLong(4, System.currentTimeMillis());
      p.setString(5, job.key());
      p.executeUpdate();
    }
  }

  synchronized boolean replaceOwners(String page, List<Owner> owners) throws SQLException {
    try (Connection c = connection()) {
      c.setAutoCommit(false);
      List<Owner> old = owners(c, page);
      if (old.equals(owners)) {
        c.commit();
        return false;
      }
      try (var d = c.prepareStatement("DELETE FROM owners WHERE page_ref=?")) {
        d.setString(1, page);
        d.executeUpdate();
      }
      try (var p = c.prepareStatement("INSERT INTO owners VALUES(?,?,?,?,?)")) {
        for (Owner owner : owners) {
          p.setString(1, owner.attachment());
          p.setString(2, page);
          p.setString(3, owner.version());
          p.setString(4, owner.key());
          p.setLong(5, System.currentTimeMillis());
          p.addBatch();
        }
        p.executeBatch();
      }
      try (var p =
          c.prepareStatement(
              "INSERT INTO reindex(page_ref,needs_index) VALUES(?,1) ON CONFLICT(page_ref) DO UPDATE SET needs_index=1")) {
        p.setString(1, page);
        p.executeUpdate();
      }
      c.commit();
      return true;
    }
  }

  private List<Owner> owners(Connection c, String page) throws SQLException {
    List<Owner> result = new ArrayList<>();
    try (var p =
        c.prepareStatement(
            "SELECT attachment_ref,page_ref,version,cache_key FROM owners WHERE page_ref=? ORDER BY attachment_ref")) {
      p.setString(1, page);
      try (ResultSet r = p.executeQuery()) {
        while (r.next())
          result.add(new Owner(r.getString(1), r.getString(2), r.getString(3), r.getString(4)));
      }
    }
    return result;
  }

  synchronized List<Owner> owners(String page) throws SQLException {
    try (Connection c = connection()) {
      return owners(c, page);
    }
  }

  synchronized List<String> pages() throws SQLException {
    try (Connection c = connection();
        var p = c.prepareStatement("SELECT DISTINCT page_ref FROM owners");
        ResultSet r = p.executeQuery()) {
      List<String> pages = new ArrayList<>();
      while (r.next()) pages.add(r.getString(1));
      return pages;
    }
  }

  synchronized void requestReconcile(String page) throws SQLException {
    try (Connection c = connection()) {
      schedule(c, page);
    }
  }

  private void schedule(Connection c, String page) throws SQLException {
    try (var p =
        c.prepareStatement(
            "INSERT INTO reindex(page_ref) VALUES(?) ON CONFLICT(page_ref) DO UPDATE SET generation=generation+1,attempts=0,available_at=0,last_error=NULL")) {
      p.setString(1, page);
      p.executeUpdate();
    }
  }

  synchronized IndexJob indexJob(long now) throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "SELECT page_ref,generation,attempts,needs_index FROM reindex WHERE available_at<=? ORDER BY available_at LIMIT 1")) {
      p.setLong(1, now);
      try (ResultSet r = p.executeQuery()) {
        return r.next()
            ? new IndexJob(r.getString(1), r.getLong(2), r.getInt(3), r.getInt(4) != 0)
            : null;
      }
    }
  }

  synchronized void indexed(IndexJob job) throws SQLException {
    try (Connection c = connection();
        var p = c.prepareStatement("DELETE FROM reindex WHERE page_ref=? AND generation=?")) {
      p.setString(1, job.page());
      p.setLong(2, job.generation());
      p.executeUpdate();
    }
  }

  synchronized void indexFailed(IndexJob job, String error, long at) throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "UPDATE reindex SET attempts=attempts+1,last_error=?,available_at=? WHERE page_ref=? AND generation=?")) {
      p.setString(1, error);
      p.setLong(2, at);
      p.setString(3, job.page());
      p.setLong(4, job.generation());
      p.executeUpdate();
    }
  }

  synchronized Map<String, Long> stats() throws SQLException {
    Map<String, Long> result = new LinkedHashMap<>();
    try (Connection c = connection();
        var p = c.prepareStatement("SELECT state,COUNT(*) FROM cache GROUP BY state");
        ResultSet r = p.executeQuery()) {
      while (r.next()) result.put(r.getString(1), r.getLong(2));
    }
    try (Connection c = connection();
        var p = c.prepareStatement("SELECT COUNT(*) FROM reindex");
        ResultSet r = p.executeQuery()) {
      r.next();
      result.put("pendingIndex", r.getLong(1));
    }
    return result;
  }

  synchronized List<Map<String, Object>> failures(int limit) throws SQLException {
    List<Map<String, Object>> result = new ArrayList<>();
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "SELECT cache_key,state,attempts,last_error FROM cache WHERE last_error IS NOT NULL ORDER BY updated_at DESC LIMIT ?")) {
      p.setInt(1, limit);
      try (ResultSet r = p.executeQuery()) {
        while (r.next())
          result.add(
              Map.of(
                  "key",
                  r.getString(1),
                  "state",
                  r.getString(2),
                  "attempts",
                  r.getInt(3),
                  "errorType",
                  r.getString(4)));
      }
    }
    return result;
  }

  synchronized void retryFailed(String profile) throws SQLException {
    try (Connection c = connection();
        var p =
            c.prepareStatement(
                "UPDATE cache SET state='pending',attempts=0,available_at=0 WHERE state='failed' AND profile_hash=?")) {
      p.setString(1, profile);
      p.executeUpdate();
    }
  }

  synchronized int forgetUnused(List<String> keys) throws SQLException {
    try (Connection c = connection()) {
      c.setAutoCommit(false);
      try {
        try (var p =
            c.prepareStatement(
                "SELECT state,EXISTS(SELECT 1 FROM owners WHERE cache_key=?) FROM cache WHERE cache_key=?")) {
          for (String key : keys) {
            p.setString(1, key);
            p.setString(2, key);
            try (ResultSet r = p.executeQuery()) {
              if (r.next()
                  && (r.getInt(2) != 0 || List.of("pending", "running").contains(r.getString(1))))
                throw new IllegalStateException(
                    "Cannot forget cache entries that are owned or unfinished");
            }
          }
        }
        int removed = 0;
        try (var p = c.prepareStatement("DELETE FROM cache WHERE cache_key=?")) {
          for (String key : keys) {
            p.setString(1, key);
            removed += p.executeUpdate();
          }
        }
        c.commit();
        return removed;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (lock.isValid()) lock.release();
    } finally {
      lockChannel.close();
    }
  }

  synchronized List<String> forgetFailed() throws SQLException {
    try (Connection c = connection()) {
      c.setAutoCommit(false);
      List<String> keys = new ArrayList<>();
      try (var p = c.prepareStatement("SELECT cache_key FROM cache WHERE state='failed'");
          ResultSet r = p.executeQuery()) {
        while (r.next()) keys.add(r.getString(1));
      }
      try (var p =
          c.prepareStatement(
              "DELETE FROM owners WHERE cache_key IN(SELECT cache_key FROM cache WHERE state='failed')")) {
        p.executeUpdate();
      }
      try (var p = c.prepareStatement("DELETE FROM cache WHERE state='failed'")) {
        p.executeUpdate();
      }
      c.commit();
      return keys;
    }
  }
}

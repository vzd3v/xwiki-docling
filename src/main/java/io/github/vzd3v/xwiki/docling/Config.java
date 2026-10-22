package io.github.vzd3v.xwiki.docling;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Validated operator configuration. Secrets are read from a private file, never logged. */
public record Config(
    Path dataDir,
    URI endpoint,
    String apiKey,
    String converterRevision,
    List<String> languages,
    String ocrEngine,
    Duration timeout,
    int workers,
    int maxAttempts,
    long retryMillis,
    long pollMillis,
    long maxInputBytes,
    long maxOutputChars,
    int maxPendingJobs,
    long maxSpoolBytes,
    String pdfBackend) {
  public Config {
    languages = List.copyOf(languages);
    if (pdfBackend != null
        && !List.of("dlparse_v1", "dlparse_v2", "dlparse_v4", "pypdfium2").contains(pdfBackend))
      throw new IllegalArgumentException("Unsupported pdfBackend");
    if (maxSpoolBytes < maxInputBytes)
      throw new IllegalArgumentException("maxSpoolBytes must be at least maxInputBytes");
  }

  public static Config load(Path path) throws IOException {
    Properties p = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      p.load(input);
    }
    List<String> allowed =
        List.of(
            "enabled",
            "dataDir",
            "endpoint",
            "apiKeyFile",
            "converterRevision",
            "languages",
            "ocrEngine",
            "timeoutSeconds",
            "workers",
            "maxAttempts",
            "retrySeconds",
            "pollMillis",
            "maxInputBytes",
            "maxOutputChars",
            "maxPendingJobs",
            "maxSpoolBytes",
            "pdfBackend");
    for (String key : p.stringPropertyNames()) {
      if (!allowed.contains(key))
        throw new IllegalArgumentException("Unknown configuration property: " + key);
    }
    String enabled = p.getProperty("enabled", "false").strip();
    if (!List.of("true", "false").contains(enabled))
      throw new IllegalArgumentException("enabled must be true or false");
    if (!Boolean.parseBoolean(enabled)) return null;
    URI endpoint = URI.create(required(p, "endpoint"));
    if (!List.of("http", "https").contains(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
      throw new IllegalArgumentException(
          "endpoint must be an HTTP(S) origin without credentials/query/fragment");
    }
    Path data = Path.of(required(p, "dataDir"));
    if (!data.isAbsolute()) throw new IllegalArgumentException("dataDir must be absolute");
    String key =
        p.containsKey("apiKeyFile")
            ? Files.readString(Path.of(p.getProperty("apiKeyFile"))).strip()
            : null;
    if (key != null && (key.isBlank() || key.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("apiKeyFile must contain a nonempty single-line key");
    String engine = p.getProperty("ocrEngine", "easyocr");
    if (!List.of("auto", "easyocr", "ocrmac", "rapidocr", "tesserocr", "tesseract")
        .contains(engine)) throw new IllegalArgumentException("Unsupported ocrEngine");
    List<String> languages =
        Arrays.stream(p.getProperty("languages", "en").split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .sorted()
            .distinct()
            .toList();
    if (languages.isEmpty()) throw new IllegalArgumentException("languages must not be empty");
    return new Config(
        data,
        endpoint,
        key,
        required(p, "converterRevision"),
        languages,
        engine,
        Duration.ofSeconds(number(p, "timeoutSeconds", 600, 1, 3600)),
        (int) number(p, "workers", 1, 1, 4),
        (int) number(p, "maxAttempts", 3, 1, 20),
        number(p, "retrySeconds", 30, 1, 3600) * 1000,
        number(p, "pollMillis", 1000, 100, 60000),
        number(p, "maxInputBytes", 52428800, 1, 1073741824),
        number(p, "maxOutputChars", 2000000, 1, 20000000),
        (int) number(p, "maxPendingJobs", 32, 1, 10000),
        number(p, "maxSpoolBytes", 536870912, 1, 107374182400L),
        p.containsKey("pdfBackend") ? p.getProperty("pdfBackend").strip() : null);
  }

  private static String required(Properties p, String key) {
    String value = p.getProperty(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing configuration: " + key);
    return value.strip();
  }

  private static long number(Properties p, String key, long fallback, long min, long max) {
    long value = Long.parseLong(p.getProperty(key, Long.toString(fallback)));
    if (value < min || value > max)
      throw new IllegalArgumentException("Out of range configuration: " + key);
    return value;
  }

  public String profile() {
    return "docling-java=0.7.0\nrevision="
        + converterRevision
        + "\nlanguages="
        + String.join(",", languages)
        + "\nengine="
        + ocrEngine
        + "\nocr=true\nforce=false\noutput=text\nimages=false"
        + (pdfBackend == null ? "" : "\npdfBackend=" + pdfBackend);
  }

  @Override
  public String toString() {
    return "Config[dataDir=" + dataDir + ", workers=" + workers + ", credentials=redacted]";
  }
}

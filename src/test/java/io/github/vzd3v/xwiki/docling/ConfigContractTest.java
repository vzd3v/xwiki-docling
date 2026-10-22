package io.github.vzd3v.xwiki.docling;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigContractTest {
  @TempDir Path directory;

  @Test
  void invalidEnableFlagAndEmptyLanguagesFailInsteadOfSilentlyChangingBehavior() throws Exception {
    Path file = directory.resolve("config.properties");
    String required =
        "dataDir="
            + directory.resolve("cache")
            + "\nendpoint=http://localhost:5001\nconverterRevision=test\n";
    for (String invalid :
        new String[] {
          "enabled=treu\n",
          "enabled=TRUE\n",
          "enabled=true\nlanguages= , ,\n",
          "enabled=true\npdfBackend=unknown\n",
          "enabled=true\npdfBackend=\n"
        }) {
      Files.writeString(file, required + invalid);
      assertThrows(IllegalArgumentException.class, () -> Config.load(file), invalid);
    }
    Files.writeString(file, "enabled=false\n");
    assertNull(Config.load(file));
    Files.writeString(file, required + "enabled=true\nlanguages=ru,en,ru\n");
    assertEquals(java.util.List.of("en", "ru"), Config.load(file).languages());
  }
}

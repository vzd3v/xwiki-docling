package io.github.vzd3v.xwiki.docling;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoclingConverterContractTest {
  @TempDir Path directory;

  @Test
  void optionalBackendChangesOnlyExplicitWireOptionAndCacheProfile() throws Exception {
    AtomicReference<String> request = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/convert/source",
        exchange -> {
          try {
            request.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response =
                "{\"status\":\"success\",\"document\":{\"text_content\":\"recognized\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      Path file = directory.resolve("input.pdf");
      Files.write(file, new byte[] {1, 2, 3});
      Path properties = directory.resolve("config.properties");
      String base =
          "enabled=true\ndataDir="
              + directory.resolve("cache")
              + "\nendpoint=http://localhost:"
              + server.getAddress().getPort()
              + "\nconverterRevision=test\nlanguages=ru,en\n";
      Files.writeString(properties, base);
      Config defaults = Config.load(properties);
      assertNull(defaults.pdfBackend());
      assertEquals(
          "docling-java=0.7.0\nrevision=test\nlanguages=en,ru\nengine=easyocr\nocr=true\nforce=false\noutput=text\nimages=false",
          defaults.profile());
      assertEquals("recognized", new DoclingConverter(defaults).convert(file, "application/pdf"));
      ObjectMapper json = new ObjectMapper();
      var oldRequest = json.readTree(request.get());
      assertFalse(oldRequest.get("options").has("pdf_backend"));
      Files.writeString(properties, base + "pdfBackend=pypdfium2\n");
      Config selected = Config.load(properties);
      assertEquals(defaults.profile() + "\npdfBackend=pypdfium2", selected.profile());
      assertNotEquals(Hashes.of(defaults.profile()), Hashes.of(selected.profile()));
      assertEquals("recognized", new DoclingConverter(selected).convert(file, "application/pdf"));
      var selectedRequest = json.readTree(request.get());
      var options = (com.fasterxml.jackson.databind.node.ObjectNode) selectedRequest.get("options");
      assertEquals("pypdfium2", options.remove("pdf_backend").asText());
      assertEquals(
          oldRequest,
          selectedRequest,
          "all other options and the original bytes must remain unchanged");
    } finally {
      server.stop(0);
    }
  }
}

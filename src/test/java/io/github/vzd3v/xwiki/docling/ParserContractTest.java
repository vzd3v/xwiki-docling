package io.github.vzd3v.xwiki.docling;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

class ParserContractTest {
  @TempDir Path directory;

  private Config config() {
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
        1000000,
        100000,
        32,
        1000000);
  }

  private static byte[] pdf() throws Exception {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream text = new PDPageContentStream(document, page)) {
        text.beginText();
        text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        text.newLineAtOffset(20, 700);
        text.showText("Native fallback remains readable");
        text.endText();
      }
      document.save(bytes);
      return bytes.toByteArray();
    }
  }

  private static Metadata metadata() {
    Metadata m = new Metadata();
    m.set(Metadata.CONTENT_TYPE, "application/pdf");
    return m;
  }

  @Test
  void cachedHandlerFailurePropagatesWithoutRetryingNativeParser() throws Exception {
    byte[] pdf = pdf();
    AtomicInteger starts = new AtomicInteger();
    SAXException expected = new SAXException("downstream limit");
    try (ConversionService service =
        new ConversionService(config(), (file, mime) -> "OCR cached text", (page, force) -> {})) {
      try (var p = service.prepare(new ByteArrayInputStream(pdf), "application/pdf")) {}
      service.start();
      long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (service.status().getOrDefault("ready", 0L) != 1) {
        assertTrue(System.nanoTime() < end);
        Thread.sleep(10);
      }
      DoclingRuntime.install(service);
      try {
        SAXException actual =
            assertThrows(
                SAXException.class,
                () ->
                    new DoclingParser()
                        .parse(
                            new ByteArrayInputStream(pdf),
                            new DefaultHandler() {
                              @Override
                              public void startDocument() throws SAXException {
                                starts.incrementAndGet();
                                throw expected;
                              }
                            },
                            metadata(),
                            new ParseContext()));
        assertSame(expected, actual);
        assertEquals(1, starts.get());
      } finally {
        DoclingRuntime.detach();
      }
    }
  }

  @Test
  void corruptedCacheAfterInputConsumptionPreservesNativePdfText() throws Exception {
    byte[] pdf = pdf();
    try (ConversionService service =
        new ConversionService(config(), (file, mime) -> "unused", (page, force) -> {})) {
      Files.write(
          directory.resolve("cache.sqlite"),
          "not a sqlite database".getBytes(StandardCharsets.UTF_8));
      DoclingRuntime.install(service);
      try {
        BodyContentHandler handler = new BodyContentHandler();
        new DoclingParser()
            .parse(new ByteArrayInputStream(pdf), handler, metadata(), new ParseContext());
        assertTrue(handler.toString().contains("Native fallback remains readable"));
      } finally {
        DoclingRuntime.detach();
      }
    }
  }
}

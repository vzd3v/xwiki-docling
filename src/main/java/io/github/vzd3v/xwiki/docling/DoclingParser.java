package io.github.vzd3v.xwiki.docling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.image.ImageParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/** Cache-first Tika SPI. Remote OCR is exclusively asynchronous. */
public final class DoclingParser implements Parser {
  private static final long serialVersionUID = 1L;
  private static final Logger LOG = LoggerFactory.getLogger(DoclingParser.class);
  private static final Set<MediaType> TYPES =
      Set.of(
          MediaType.application("pdf"),
          MediaType.image("png"),
          MediaType.image("jpeg"),
          MediaType.image("tiff"),
          MediaType.image("bmp"),
          MediaType.image("gif"),
          MediaType.image("webp"));

  @Override
  public Set<MediaType> getSupportedTypes(ParseContext context) {
    return TYPES;
  }

  static boolean supports(String mime) {
    try {
      return TYPES.contains(MediaType.parse(mime));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public void parse(
      InputStream input, ContentHandler handler, Metadata metadata, ParseContext context)
      throws IOException, SAXException, TikaException {
    try (TemporaryResources resources = new TemporaryResources()) {
      TikaInputStream replay = TikaInputStream.get(input, resources, metadata);
      // Native PDF parsing already needs random access. Tika owns this spool and its cleanup.
      Path file = replay.getPath();
      String mime = metadata.get(Metadata.CONTENT_TYPE);
      if (mime == null || !supports(mime)) {
        try (TikaInputStream detected = TikaInputStream.get(file)) {
          mime =
              new org.apache.tika.detect.DefaultDetector()
                  .detect(detected, metadata)
                  .getBaseType()
                  .toString();
        }
      }
      ConversionService service = DoclingRuntime.get();
      String cached = null;
      if (service != null) {
        try {
          cached = service.cachedOrEnqueue(file, mime);
        } catch (Exception e) {
          LOG.warn(
              "component=xwiki-docling event=cache_unavailable errorType={}",
              e.getClass().getName());
        }
      }
      if (cached != null && !cached.isBlank()) {
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();
        xhtml.element("p", cached);
        xhtml.endDocument();
        return;
      }
      try (InputStream nativeInput = Files.newInputStream(file)) {
        if (mime.equals("application/pdf"))
          new PDFParser().parse(nativeInput, handler, metadata, context);
        else new ImageParser().parse(nativeInput, handler, metadata, context);
      }
    }
  }
}

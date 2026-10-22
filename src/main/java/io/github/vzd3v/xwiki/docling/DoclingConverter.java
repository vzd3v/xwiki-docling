package io.github.vzd3v.xwiki.docling;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.OcrEngine;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import ai.docling.serve.client.DoclingServeClientException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** The only SDK boundary. Uses file bytes; the remote service never fetches wiki URLs. */
final class DoclingConverter implements Converter {
  private final DoclingServeApi api;
  private final ConvertDocumentOptions options;
  private final long maxOutput;

  DoclingConverter(Config config) {
    var builder =
        DoclingServeApi.builder()
            .baseUrl(config.endpoint())
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(config.timeout())
            .logRequests(false)
            .logResponses(false);
    if (config.apiKey() != null) builder.apiKey(config.apiKey());
    api = builder.build();
    options =
        ConvertDocumentOptions.builder()
            .toFormats(List.of(OutputFormat.TEXT))
            .doOcr(true)
            .forceOcr(false)
            .ocrEngine(OcrEngine.valueOf(config.ocrEngine().toUpperCase(Locale.ROOT)))
            .ocrLang(config.languages())
            .includeImages(false)
            .build();
    maxOutput = config.maxOutputChars();
  }

  @Override
  public String convert(Path file, String mime) throws Exception {
    String extension =
        mime.equals("application/pdf")
            ? "pdf"
            : mime.substring(mime.indexOf('/') + 1).replace("jpeg", "jpg");
    var source =
        FileSource.builder()
            .filename("attachment." + extension)
            .base64String(Base64.getEncoder().encodeToString(Files.readAllBytes(file)))
            .build();
    try {
      var response =
          api.convertSource(
              ConvertDocumentRequest.builder().source(source).options(options).build());
      if (!(response instanceof InBodyConvertDocumentResponse body) || body.getDocument() == null)
        throw new ConversionFailure("missing_document", false);
      if (!"success".equals(body.getStatus()))
        throw new ConversionFailure("conversion_not_successful", false);
      String text = body.getDocument().getTextContent();
      if (text == null) throw new ConversionFailure("missing_text", false);
      if (text.length() > maxOutput) throw new ConversionFailure("output_limit", false);
      return text;
    } catch (DoclingServeClientException e) {
      int status = e.getStatusCode();
      throw new ConversionFailure(
          status > 0 ? "http_" + status : "transport_error",
          status < 0 || status == 408 || status == 429 || status >= 500,
          e);
    }
  }
}

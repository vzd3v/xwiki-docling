# XWiki Docling

Asynchronous attachment OCR for XWiki, powered by an existing [Docling Serve](https://github.com/docling-project/docling-serve) service.

Scanned PDFs and images become searchable through XWiki's ordinary search and the native LLM Index. A Tika adapter serves cached text to both extraction paths; a background worker converts missing files and requests indexing of the affected page. Original pages, attachments, permissions and vector storage stay in XWiki.

**Early release:** use a test wiki before production. This is an independent global extension, installed as one JAR with a web restart. Installation through Extension Manager and clustered XWiki are not supported.

## How it works

1. A supported attachment is added, changed, explicitly reconciled, or parsed by Tika.
2. Its bytes and conversion settings identify a durable local cache entry.
3. A background worker submits the bytes through the official Docling Java client. On a cache miss, native PDF/image extraction remains available immediately.
4. Successful conversion stores plain text and requests native indexing for the current owning page. Replaced or deleted attachments lose their old ownership mapping.

Identical bytes with identical settings share one conversion. Restart resumes unfinished work. Successful input spools are deleted; extracted text remains local. Changing the wiki origin does not invalidate cache keys or owner references.

For attachments that already exist when installing, run the bounded [initial reconciliation](docs/ops/operations.md). Tika sees file bytes without the owning page reference; parsing alone cannot register old owners or reliably schedule their later indexing.

Supported OCR routing: PDF, PNG, JPEG, TIFF, BMP, GIF and WebP. Office and text formats keep their native parsers. The native MCP image-reading tool returns an image; this extension does not change that tool into an OCR-text endpoint.

## Get started

- [Install and remove](docs/ops/install.md)
- [Configuration](docs/ops/configuration.md)
- [Warm existing attachments and diagnose failures](docs/ops/operations.md)
- [Implementation and lifecycle](docs/dev/architecture.md)
- [Build and tests](CONTRIBUTING.md)
- [Security and private data](SECURITY.md)

```sh
mvn -B -ntp spotless:check verify
```

The artifact is `target/xwiki-docling-0.1.0.jar`. Java 21+ and Maven 3.9+ are required to build. XWiki 18.8 API classes require Java 21. XWiki and Tika are provided by the host application; Docling SDK/Jackson are isolated inside the artifact. SQLite JDBC retains its native package for JNI compatibility.

## Compatibility

The initial target is XWiki 18.8.0, Java 21, Tika 3.3.2, LLM Index/MCP 0.11 and Docling Serve 1.37.0. Live acceptance status is recorded before a release is published. Other combinations require validation; Java compilation compatibility alone does not prove XWiki runtime compatibility.

This project is independently maintained and is not an official XWiki or Docling product. [MIT license](LICENSE); bundled dependencies retain their own licenses.

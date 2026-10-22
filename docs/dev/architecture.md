# Implementation

The package registers `DoclingParser` through Tika's parser SPI. Both native XWiki extraction paths select it for supported MIME types. It reads cached plain text; misses enqueue conversion and return native extraction. Handler exceptions propagate normally. A replayable Tika spool preserves native parsing if cache/storage fails. The adapter borrows that file for hashing; a cache hit creates no second attachment copy. A miss copies only an admitted input into the persistent conversion spool.

`XWikiDocling` owns the lifecycle and administrator boundary. The separate `XWikiDoclingEvents` listener injects that singleton. Document events enqueue the current canonical page reference, including locale. A background job establishes a fresh XWiki execution context, loads current attachment metadata, updates ownership atomically, and invokes native indexers. It never reuses a request's XWiki context across threads.

`ConversionService` coordinates durable conversion and indexing work. `DoclingConverter` is the single official SDK boundary. Files are uploaded as bytes; Docling does not fetch authenticated wiki URLs. Only successful in-body text responses are cached. Partial/error responses keep native fallback and follow bounded retries.

[`ddl.sql`](../../src/main/resources/db/ddl.sql) defines SQLite schema version 1. Cache identity is input hash plus effective conversion profile and MIME. Owners refer to current canonical page/attachment/version. Completion commits extracted text and index outbox generation together. An old indexing acknowledgment cannot remove newer work. Replacement/deletion detaches the old content address; a late conversion cannot index it as the replacement's text.

Native ordinary indexing targets the owning document and its own translated/attachment/object references. Native semantic work is submitted as `llm_xwiki_document`, preserving native collection selection, hidden-page behavior and embedding reuse. No other page's text or permissions are written. The native indexer's readiness mechanism is awaited; its own logs and an actual query remain necessary validation of search visibility.

One JVM owns one SQLite directory through an operating-system file lock. Interrupted conversions return to pending after restart; completed text and the index outbox persist. Profile changes do not process old pending bytes under new options. This is not a clustered coordination layer.

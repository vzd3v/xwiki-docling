# Configuration

Set the JVM property `-Dxwiki.docling.config=/private/path/docling.properties`. With no property, or `enabled=false`, extraction remains native and no worker/store is opened. Restart the web application after changing configuration.

Copy and adapt [the example](../../examples/docling.properties). Protect the configuration and optional API key file with the runtime user's permissions. Unknown properties and invalid required values fail extension initialization; inspect the XWiki log for `component=xwiki-docling`.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Explicitly enable the extension |
| `dataDir` | required | Absolute persistent private directory, one JVM only |
| `endpoint` | required | Trusted Docling Serve HTTP(S) base URL, without credentials/query |
| `apiKeyFile` | absent | Private UTF-8 file containing the optional service API key |
| `converterRevision` | required | Operator-managed revision of the server/model pipeline; change when output can change |
| `languages` | `en` | Comma-separated OCR languages |
| `ocrEngine` | `easyocr` | SDK OCR engine; installed server must support it |
| `workers` | `1` | Concurrent conversion requests, range 1–4 |
| `timeoutSeconds` | `600` | Maximum wait for conversion and native indexing, range 1–3600 |
| `maxAttempts` | `3` | Conversion attempts before explicit failure, range 1–20 |
| `retrySeconds` | `30` | Initial exponential retry delay, capped at one hour |
| `pollMillis` | `1000` | Worker queue polling interval, range 100–60000 |
| `maxInputBytes` | `52428800` | Largest file submitted for OCR; larger files use native extraction |
| `maxOutputChars` | `2000000` | Largest accepted conversion text |
| `maxPendingJobs` | `32` | Maximum stored input files, including pending/running/failed jobs |
| `maxSpoolBytes` | `536870912` | Maximum accepted persistent input bytes; must be at least `maxInputBytes` |

Output is plain text, OCR enabled, forced OCR disabled, and image output disabled. Cache identity includes the input hash, MIME type, SDK version, converter revision and canonical conversion options. The endpoint and wiki URL are deliberately excluded. Changing the service behind an endpoint requires a new `converterRevision` if behavior changes.

Old-profile pending conversions are marked failed with `inactive_profile` rather than processed with new settings. Inspect failures and explicitly forget those obsolete inputs before reconciling the affected pages; completed text from other profiles remains available for rollback. Pending and failed input spools share the capacity limits. Successful conversion removes its raw input spool; interrupted temporary copies are cleaned after acquiring the runtime lock on restart.

When capacity is full, parsing falls back to native extraction. The owning page's durable reconciliation intent backs off and resumes as conversion frees capacity; no busy retry loop or full-corpus raw copy is created. Temporary hashing/native Tika spools are outside the persistent input cap.

The parser uses Tika's native temporary-file mechanism for replayable input, then bounds the extension's own spooling. Native PDF parsing may itself spool large inputs to disk; `maxInputBytes` is not a global XWiki upload limit.

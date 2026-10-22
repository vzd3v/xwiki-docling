# Security

Do not post credentials, private attachments, extracted text or internal deployment addresses in public issues. Report suspected vulnerabilities through GitHub's private vulnerability reporting for this repository.

The extension runs with the XWiki JVM's operating-system access. Its configured Docling service receives original supported attachment bytes. Use a trusted service inside the appropriate data boundary and HTTPS or a protected private network. The service never receives a wiki download URL or user token.

The private data directory contains extracted text, attachment identifiers, pending/failed input spools and queue state. Restrict it to the XWiki runtime user. Include it in the same retention and backup policy as other sensitive derived wiki data. Never serve this directory over HTTP or share it between JVMs. The extension refuses a second runtime using the same directory.

Native XWiki search and MCP enforce page permissions. Cached text is not exposed through a new HTTP endpoint. Administrator diagnostics return counts and sanitized error types, not text or API keys. Content hashes may still identify known documents: treat diagnostics and logs as private.

This release is single-node. It does not provide encrypted local storage, distributed locking or an independent authorization system. Upgrading the configured converter or changing OCR options requires targeted reconciliation because conversion output is derived from those settings.

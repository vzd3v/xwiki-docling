# Install and remove

Use a disposable test wiki before production. This package is installed globally through the web application's classpath; Extension Manager installation and hot reload are unverified.

## Install

1. Build with `mvn -B -ntp spotless:check verify` or obtain the reviewed release artifact.
2. Place the JAR in the XWiki web application's `WEB-INF/lib`. Persist this addition through your normal image or deployment mechanism; copying into a disposable container layer alone is insufficient.
3. Create a private persistent data directory writable by the XWiki process. Do not share it with another JVM or expose it over HTTP.
4. Write the private configuration and set `-Dxwiki.docling.config=/private/path/docling.properties` in the web JVM options. Grant access from that JVM to the trusted Docling service.
5. Restart the web application using the normal XWiki maintenance procedure. Commit/drain native indexes as required by your deployment before restarting.
6. Check for `component=xwiki-docling event=started` and use [administrator diagnostics](operations.md). Upload a disposable scanned PDF and verify its scan-only phrase through the intended native search and MCP paths.

The JAR includes the parser SPI registration and XWiki lifecycle component. No native Tika XML replacement or modified XWiki classes are required. Keep only one version of this JAR on the classpath.

## Validate

Check cache hits, conversion retries, replacement/deletion, restart persistence and search rights with synthetic files. Validate a normal PDF and Office document as controls. Confirm the extension does not monopolize a shared Docling service before raising `workers`.

## Remove or roll back

Set `enabled=false` and restart, or remove the JAR and its JVM property through the deployment mechanism and restart. Native extraction remains available. Keep the private data directory if rolling back to the same configuration later.

Existing native indexes can retain OCR-derived text until the affected pages are reconciled by the native indexers. Do not clear an entire index merely to disable this extension. Original articles and attachments are never rewritten by the extension.

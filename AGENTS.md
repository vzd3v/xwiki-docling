# Contributor automation

Portable Java extension for a single XWiki JVM. Original wiki documents are authoritative; SQLite stores recoverable derived data and durable work. Reuse native XWiki rights and indexers.

- Keep plans, specifications, reviews, deployment-specific configuration and acceptance reports outside this repository and its history.
- Never add internal hosts, private URLs, credentials or document content.
- Use schema-first changes and preserve pending jobs across restart.
- Run `mvn -B -ntp spotless:check verify` after source changes.
- Use meaningful contract tests for cache identity, races, fallback, lifecycle and persistence.
- Never patch third-party source or broaden production deployment scope.
- Independent review and live disposable-wiki acceptance must approve the exact intended public revision before publication.

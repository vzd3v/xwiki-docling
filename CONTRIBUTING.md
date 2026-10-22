# Contributing

Open an issue for a reproducible defect or a concrete improvement. Remove private document content, addresses, credentials and logs containing personal data. Security reports follow [SECURITY.md](SECURITY.md).

## Development

Use Java 21+ and Maven 3.9+. Dependencies and plugins are pinned in `pom.xml`.

```sh
mvn -B -ntp spotless:apply
mvn -B -ntp spotless:check verify
```

Tests cover durable queue recovery, cache identity, bounded retries, stale ownership, indexing generations and native extraction fallback. Test only external boundaries; do not replace XWiki or Docling internals with test-only behavior. Live acceptance needs a disposable XWiki and synthetic documents; preserve existing articles.

Submit a focused pull request with the problem, resulting behavior and checks performed. Update configuration and operational docs when their contract changes. Keep infrastructure configuration, credentials, personal research, plans, specifications and private acceptance reports outside this repository and its history. Do not patch XWiki, Tika or Docling source in this project.

Before a public release, independently review the exact revision, run the documented live acceptance, check dependency notices and scan the full intended history for secrets. Never disable TLS verification to fetch dependencies.

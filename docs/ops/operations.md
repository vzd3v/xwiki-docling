# Operations

Administrator controls are an XWiki component, available from an administrator-owned Groovy script with programming rights. They are not a new public HTTP API. The methods require main-wiki ADMIN because the cache is shared across subwikis; a reconcile also checks target-wiki ADMIN.

```groovy
def admin = com.xpn.xwiki.web.Utils.getComponent(
    Class.forName('io.github.vzd3v.xwiki.docling.XWikiDocling'), 'xwiki-docling')
println admin.status()
println admin.failures(20)
admin.reconcile('xwiki:Example.Page')
// After fixing a converter/service failure:
// admin.retryFailed()
// Explicitly discard permanently failed/obsolete derived inputs to free capacity:
// admin.forgetFailed()
```

`reconcile` records a durable per-page job and returns immediately. It hashes supported attachments on that page, reuses conversions for identical bytes and settings, and requests native ordinary/semantic indexing. It does not recreate a collection or trust attachment revision strings as content hashes. Repeat calls are safe. Native semantic indexing is optional; when present its own task consumer determines matching collections and hidden-page behavior.

For existing articles, use an explicit list of canonical page references. This administrator-owned Groovy example admits at most ten page intents per run and stops feeding when twenty page intents are already pending. Edit the list and retain the printed `nextOffset` in your private operational notes; restart at that offset. Repeating a batch is safe because intents and conversions are deduplicated.

```groovy
def admin = com.xpn.xwiki.web.Utils.getComponent(
    Class.forName('io.github.vzd3v.xwiki.docling.XWikiDocling'), 'xwiki-docling')
def pages = ['xwiki:Manual.Installation.WebHome',
             'xwiki:Manual.Troubleshooting.WebHome']
int offset = 0 // Set to the previous run's nextOffset.
int batchSize = 10
int admitted = 0
while (offset < pages.size() && admitted < batchSize) {
    if ((admin.status()['pendingIndex'] ?: 0L) >= 20L) break
    admin.reconcile(pages[offset])
    offset++
    admitted++
}
println([admitted: admitted, nextOffset: offset, total: pages.size(),
         status: admin.status(), failures: admin.failures(10)])
```

A cursor acknowledges durable page intents, not completed OCR. After all pages are admitted, wait for `pendingIndex` and `pending`/`running` conversion counts to reach zero, inspect `failed` and `failures(20)`, then search a known scan-only phrase through ordinary and semantic search. A nonzero failed count requires repair or explicit disposal as described below. The worker limits remote concurrency and spool capacity; watch the shared converter's latency before feeding more. Reconcile only relevant spaces or pages. Do not clean or rebuild an entire index.

New uploads, replacements and deletions enqueue their owning page automatically. Restart resumes durable pending work without scanning or reindexing all healthy pages. If the extension was disabled during edits, reconcile those changed pages explicitly afterward.

`status()` returns counts by conversion state and `pendingIndex`. `failures(n)` returns sanitized conversion failures and attempts; raw remote error bodies, extracted text and secrets are excluded. Conversion retries back off exponentially and stop after `maxAttempts`; `retryFailed()` explicitly resumes them after the cause is fixed. Indexing failures retain their outbox entry and retry.

`forgetFailed()` removes only failed derived cache records, their owner mappings and private input spools. Original wiki attachments and successful OCR entries remain intact. Inspect failures first; use retry after repairing a transient service problem, or forget permanently invalid/old-profile inputs. Reconcile a page later to attempt conversion from its original attachment again. Failed inputs consume the configured capacity until retried successfully or explicitly forgotten.

Logs use the XWiki SLF4J pipeline with `component=xwiki-docling`, event, content-address job ID, attempts and elapsed conversion time. Native indexing failures should also be checked in XWiki's indexer logs. An empty queue is not proof of searchable content: query a known scan-only phrase after deployment or repair.

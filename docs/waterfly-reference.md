# Waterfly III (dreautall/waterfly-iii) — proven Firefly III client patterns

Reference clone: `/opt/data/ref/waterfly-iii` (Flutter/Dart; its API layer is GENERATED
from Firefly III's official OpenAPI spec → the request/response shapes are authoritative).
Adapt idioms to Cashiro's Kotlin/Ktor; do not translate Dart literally.

## 1. Attachments — the real v6 flow (fixes our M6)
`lib/pages/transaction/dialogs/attachments.dart` `uploadAttachment()`:
1. `POST /api/v1/attachments` with body:
   `{"title": <name>, "notes": ..., "attachable_type": "transaction_journal", "attachable_id": <JOURNAL id>}`
   (attachable type is **transaction_journal**, id is the journal id — NOT the transaction group id).
2. Response `data.attributes.upload_url` (also `embed_url`, `url` in later versions).
3. Upload: streamed **binary** POST (content-type application/octet-stream) with the same
   Authorization headers to `upload_url`. Firefly accepts both POST and PUT to upload_url;
   Waterfly uses POST with explicit contentLength and streams progress.
4. Failure handling: parse `ValidationErrorResponse` from the create step's error body.
There is NO `/attachments/{id}/upload` route — our current code (FireflyClient ~1378) calls
a route that does not exist → every upload 404s. Cashiro already carries `journalIds` from
create/update Success results (`FireflyClient.kt:177,262,1199`), so the journal id is available.

## 2. Pagination (fixes our M5)
Waterfly never asks for huge limits: it pages with `page` + `limit` query params
(`v1TransactionsGet(page, limit, start, end, type)` in `lib/stock.dart`) and caches per-page;
response envelopes carry `meta.pagination.{current_page, next_page, last_page, total}` —
loop while `current_page < last_page` (or follow `next_page_url` in `links`).
Default page size in Firefly is 50; limit>50 is allowed but instances may cap it — page until
`last_page`, never a single `limit=1000` call. Our M5: categories, budgets, budget-limits,
accounts all need the loop.

## 3. Search & idempotency
- Waterfly is the sole writer, so it does NOT dedupe via external_id search — it stores the
  Firefly transaction-group `id` locally and uses `v1TransactionsIdGet/Put/Delete` directly.
- Cashiro is a co-writer (users edit on the web too), so the external_id search path stays —
  but prefer `GET /api/v1/transactions/{group-id}` when a sidecar row already knows the remote
  id (cheap, no search rate limit), and reserve `/search/transactions?query=external_id_is:*`
  only for the unknown-id case.
- Search query grammar: queries are space-separated field searches; `external_id_is:"..."`
  is exact-match (`FireflyClient` already uses this correctly).

## 4. Error envelopes
All JSON:API responses: success `{ "data": {...}, "meta": {...} }`; errors
`{ "errors": { "<field>": ["msg", ...] } }` + top-level `message`. 422 = validation (usually
unknown account/rule); 401/403 = token; 404 definitive; 429 = rate limit (no Retry-After
guarantee — back off exponentially). Waterfly parses ValidationErrorResponse for user-facing
messages; Cashiro should surface field-level errors into `markError` instead of generic text.

## 5. What Waterfly does NOT have (our H1/H2 work goes beyond it)
No 429 retry policy, no single-flight guard, no offline queue — those are our own additions;
don't "copy" a simpler pattern over our stronger one.

## 6. Transaction model notes
- A Firefly "transaction" is a GROUP (`/transactions` id) containing journals
  (`attributes.transaction-journals[]`, each with own id); splits are journals in the same group.
- `apply_rules`/`fire_webhooks` default **true** on POST/PUT — Waterfly leaves defaults; our
  L1 finding (silent rule mutation of synced rows) could be fixed by sending
  `apply_rules=false` on push and letting the user's rules run only on web-side edits.

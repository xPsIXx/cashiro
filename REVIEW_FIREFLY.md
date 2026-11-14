# Cashiro — Firefly III Sync Add-on: Code Review

**Repo:** `/opt/data/cashiro` (fork of PennyWise / `sarim2000/pennywiseai-tracker`)
**HEAD reviewed:** `4e613d1f` — "fix(firefly): persist sidecar sync state on first sync + live JVM E2E"
**Delta base (merge-base with upstream):** `6758f607` → 41 commits, 97 files, ~+9258/−821
**Date:** 2026-11-06
**Method:** Two independent read-only static reviews (architecture/correctness + security/hygiene), no gradle builds. Findings cite `file:line` from code personally read by the reviewer; items marked **[verified]** were independently re-confirmed against the working tree before inclusion. Judged on Kyle's delta only, not upstream code.

---

## Verdict: **problematic as-is**  → *update 2026-11-13: ALL findings fixed and verified by a real test run — C1/H1-H3 (99bf3cd6, 198be74b, eb447085), M5/M6/L1 (8db6f3c4), M1-M3/S1/S2 (5fd922e1), M4/S3-S7 (690d956b), L2/L3 (ede3ac8f). First full JVM suite executed in cashiro-buildbox: **999 tests / 140 classes, 0 failures, 0 errors, 2 skipped** (env-gated: live E2E needs FIREFLY_PAT; real keystore unavailable under Robolectric). The real run then exposed 5 further real bugs, all fixed: remote search could never return Found (per-row decode), links.next fallback unreachable in both pagination loops, null-cutoff candidate SQL dropped never-synced rows, apply_rules/fire_webhooks silently omitted from push payloads (encodeDefaults=false dropped the DTO defaults — L1 guard had zero coverage), TokenManager this-escape in flow seeding. M2 final semantics: pulled rows KEEP their local updatedAt (remote updated_at lives only in sidecar fireflyUpdatedAt — stamping it on the row would make every pull re-qualify as stale under M4). Remaining: L4 budget-import nits.** Sidecar QA round (2026-11-13, late):** full audit of the sidecar data floors found and fixed more real issues — (1) HIGH: hard deletes + SQLite rowid reuse could hand a dead row's Firefly journal mapping to a brand-new transaction (fixed: immediate cleanup in every hard-delete path + `pruneOrphanedSidecarState` safety net at resync start; 06849571); (2) candidate batch starvation of old errored rows (error-first SQL ordering) and unbounded `firefly_log` (trimLogs(2000)); (3) piggy-banks/recurrences still single-page limit=1000 pulls (now via paginate()), budget `inferPeriodType` now honors the budget's declared `auto_budget_period` when the limit span agrees, and a strict `LocalDate.parse` that silently dropped spec-shaped date-time budget limits/recurrences (parseDate now tolerant). Suite: **1019 tests / 144 classes, 0 failures, 0 errors, 2 env-gated skips** (test-run-15). **Live E2E also executed for real 2026-11-14 against the owner's production Firefly instance (v6.7.9, base URL supplied via env, never committed): `pushCreateThenPullRemoteEditIntoAppRow` RAN, 20.4s, 0 failures — push → idempotent skip → remote edit → pull into the app's Room row verified end-to-end, journal self-deleted by teardown.** CI-era gates also run: `lintStandardDebug` green — zero lint errors in the `firefly/` add-on package (all 120 errors / 753 warnings are pre-existing upstream PennyWise code; the TrustAll hits are warnings inside the transitive bcpkix jar, not app code — S6 verified) and `assembleStandardRelease` green (unsigned arm64/armv7 APKs built). All findings now closed.*

Fresh installs boot and basic sync works, but:
1. There is a **release-blocker** — existing installs crash at startup on upgrade (invalid migration SQL).
2. The sync engine has a cluster of **data-integrity bugs** that will duplicate or silently corrupt user financial data under real usage (rate limits, concurrent runs, failed updates).

The design bones are sound and several subsystems are genuinely solid (see "Solid — leave alone"). Fix the CRITICAL first, then the HIGHs.

**Severity legend:** 🔴 CRITICAL · 🟠 HIGH · 🟡 MEDIUM · ⚪ LOW/nit

---

## 🔴 CRITICAL

### C1. Every existing install crashes on upgrade — invalid double-backtick SQL in rebuild migrations **[verified] — ✅ FIXED 2026-11-13**

> **Fix summary (2026-11-13):** The six near-duplicate `MIGRATION_47..52_67` blocks were replaced by one schema-introspecting `consolidateToForkEra67()` (+`rebuildTableAt67`, `addColumnIfMissing`, `TARGET_COLUMN_DEFS`). Executing the real SQL against reconstructed fork-era schemas exposed **two crash classes beyond the backticks**: (a) 49/50 edges hardcoded `excluded_from_analytics` in their copy list although the column only arrives at 50→51 (`no such column`), (b) 48..52 edges re-ADD `match_type` the fork chain had already applied (`duplicate column name`); the 49 edge also never covered `account_balances.alias` (Room validation would reject the migration). New code: rebuild copies the intersection of live∩target columns, all ADDs are PRAGMA-guarded, missing target columns get ALTER'd with defaults, and already-synced `firefly_*` state is staged into `firefly_sync_state_staging` before the drop (closes the H1 post-upgrade duplicate-storm aggravator; app-side sidecar backfill from the staging table is still TODO). Verified: `tools/migration_harness.py` (python mirror, executes all 5 edges twice against 47/49/50/51/52 reconstructed schemas — all pass) and `ForkEraMigrationEdgesTest` (Robolectric JVM test with fixtures generated from the Room schema exports). No JDK on this host, so `gradlew test` still needs to run on a dev machine/CI.

**Original finding:**
- **Where:** `PennyWiseDatabase.kt` — rebuild-style migrations `MIGRATION_47_67 … MIGRATION_52_67` (defs at :756, :823, :902, :989, :1076, :1163). **91 lines** contain double-backtick identifiers, e.g. `:762`, `:829`:
  ```sql
  CREATE TABLE IF NOT EXISTS ``transactions_new`` (...)   -- ``x`` = EMPTY identifier in SQLite
  ...
  CREATE UNIQUE INDEX ... ON ``transactions`` (`transaction_hash`)
  ```
- **Why it breaks:** SQLite treats backticks as identifier quotes, so ` ``transactions_new`` ` is an *empty* identifier → `SQLiteSyntaxException` on the first `execSQL`. Room 2.6.1's `findUpMigrationPath` iterates `targetNodes.descendingKeySet()`, so for any install at v47–v52 the **direct N→67 edge always beats the upstream chain** — every existing Cashiro user hits this at startup. There is no `fallbackToDestructive` (builder at `PennyWiseDatabase.kt:1283-1297`).
- **Note:** The rebuild design itself is otherwise correct — each `INSERT … SELECT` column list exactly matches the fork-era schema (`47.json`/`46.json`) and preserves data. So the fix is just to strip the extra backticks. No migration test covers these edges (only Firefly DAO/E2E tests exist).
- **Fix:** Remove the stray double-backticks (make them single `` `identifier` ``); add a migration test that drives a v47 and a v52 DB through to 67.

---

## 🟠 HIGH — data integrity

### H1. Any search failure is treated as "not on Firefly" → duplicate transaction creation — ✅ FIXED 2026-11-13 (commit 198be74b)

> Sealed `ExternalIdLookup{Found|NotFound|Transient}`; Transient defers the item (left unsynced, `markError` recorded) instead of creating. `installFireflyRetryPolicy`: 429 retried any-method with backoff + Retry-After; 5xx only GET/HEAD/DELETE; POST/PUT only on pre-commit connect-phase failures — never a blind re-POST. Post-upgrade mass-resync aggravator neutralized by C1 staging + backfill (eb447085).

**Original finding:**
- **Where:** `FireflyClient.kt:1381-1393` (`getRemoteTransactionByExternalId` returns null for **any** non-success status — 429/5xx — or exception); `:1061` then adds the item to `toCreate`. Ktor retry plugin (`:108-112`) retries only 5xx + `IOException`, **not 429**.
- **Why it breaks:** Firefly III rate-limits `/search/transactions`, and the search phase is serial per transaction (`:1056` forEach). Any backlog of unsynced transactions exceeds the limit → 429 → null → **CREATE duplicates at scale**. Aggravator: post-upgrade, fork users' `firefly_*` state columns are dropped by the migrations without being copied into the sidecar, so every previously-synced transaction re-enters `resyncAll` and re-searches in one mass run. Additionally `retryOnExceptionIf(IOException)` (`:110`) re-POSTs a create that already committed server-side after a client timeout — Firefly does **not** dedupe by `external_id` on POST `/transactions`.
- **Fix:** Distinguish "not found" (404) from "transient/limit" (429/5xx) — treat the latter as *unknown*, not *absent*; retry 429 with backoff; make creates idempotent (or search-by-external_id immediately before create in the same guard).

### H2. No single-flight guard: concurrent sync paths race on search-then-create — ✅ FIXED 2026-11-13 (commit eb447085)

> `runBatch` claims txIds in a `ConcurrentHashMap.newKeySet` before any HTTP; concurrent attempts return SKIPPED with zero HTTP; claims released in `finally`; hook + resyncAll + retryFailedTxs share one guard (per-process; WorkManager here is single-process).

**Original finding:**
- **Where:** No `Mutex`/lock/single-flight anywhere in `data/firefly/` or the workers. `onTransactionSaved` (`FireflySyncEngine.kt:119`) is a plain suspend call executed synchronously inside `AddTransactionUseCase.kt:122` and `SmsTransactionProcessor.kt:290`, while `FireflyAutoSyncWorker` also runs `resyncAll()` periodically.
- **Why it breaks:** Two runs touching the same unsynced row both search (→ null) before either create commits → duplicate on Firefly. No per-row or global in-flight set prevents this.
- **Fix:** A per-txId in-flight set / `Mutex` around the search-then-create critical section, shared between the hook path and the worker.

### H3. Partial-failure recovery pipeline is dead code: failed updates are never retried or surfaced **[verified] — ✅ FIXED 2026-11-13 (commits 198be74b + eb447085)**

> `markError` is now an upsert (root cause: the state row didn't exist before the first failure) wired into all four failure branches; `resyncAll` also picks up `lastError != null` rows. RetryWorker/FailedSyncsViewModel are live. Side effect: inconclusive searches now surface as `lastError = "search inconclusive: …"` — filter on that prefix if the UI should distinguish defer vs hard failure.

**Original finding:**
- **Where:** `FireflySyncDao.markError` (`FireflySyncDao.kt:56`) is declared but **never called from production code** (only its declaration exists — re-confirmed via `rg`). So `statesWithError()` (`:74`) is always empty → `FireflyRetryWorker` is a no-op and `FireflyFailedSyncsViewModel` (`:38`) is permanently blank.
- **Why it breaks:** The update-failure branch (`FireflyClient.kt:1197-1206`) leaves the sidecar state untouched, so a row that once synced successfully but whose later update failed is skipped forever by `resyncAll`'s `syncedAt == null` filter (`FireflySyncEngine.kt:164`) — local and remote silently diverge with no retry path. `runBatch` still enqueues `FireflyRetryWorker` on failures (engine ~`:337`), which does nothing.
- **Fix:** Call `markError(txId, msg)` in the failure branches; make `resyncAll` also pick up rows with an error state (not only `syncedAt == null`).

---

## 🟡 MEDIUM

### M1. ✅ FIXED 2026-11-13 (5fd922e1): single buildSyncable() group builder shared by hook + resync/retry paths
- **Where:** `AddTransactionUseCase.kt:122` and `SmsTransactionProcessor.kt:290` call `onTransactionSaved(entity.copy(id=...))` without splits; `FireflySyncEngine.kt:119` defaults `splits` to `emptyList()`.
- **Why it breaks:** The hook path pushes the total amount as one row with no split group. Since the row is then marked synced, later resyncs (which *do* load splits) skip it — the split structure is never corrected on Firefly.
- **Fix:** Load and pass splits in the hook path (or defer split-bearing rows to the full sync path).

### M2. ✅ FIXED 2026-11-13 (5fd922e1): remote date + local time-of-day preserved; EXPENSE<->CREDIT/INVESTMENT collapse blocked; updatedAt = remote updated_at ?: existing
- **Where:** `FireflyClient.kt:1845` — `pullRemoteTransactionIntoLocal` sets `dateTime = remote.date?.atStartOfDay()` (Firefly stores date-only, so every PULL resets local time to 00:00). Also rewrites `transactionType` via `mapRemoteTypeToLocal` (`:1856`), which collapses CREDIT/INVESTMENT → EXPENSE, and passes `syncedAt` as the row's `updatedAt` through `TransactionRepository.updateTransactionFromFirefly` (`:67`: `updatedAt = syncedAt`), conflating sync time with edit time.
- **Fix:** Preserve local time-of-day on pull; map types losslessly or guard against downgrades; don't overwrite `updatedAt` with the sync timestamp.

### M3. ✅ FIXED 2026-11-13 (5fd922e1): persisted sidecar externalId > pennywise-<hash> > pennywise-ff-<UUID> persisted at first push; engine pins, client resolves from stateByTx
- **Where:** `FireflyClient.kt:1554-1560` — `computeExternalId` falls back to ``pennywise-${tx.id}`` (local autoincrement) when `transactionHash` is null.
- **Why it breaks:** A backup/restore or device transfer renumbers rows → dedup search misses → duplicate creates. Any edit that changes the stored `transactionHash` changes the `external_id`, orphaning the old remote row and creating a new one. Only hash-bearing (SMS/autopay) transactions are stable.
- **Fix:** Use a stable, content-derived external id (or persist an explicit UUID column) rather than the autoincrement PK.

### M4. ✅ FIXED 2026-11-13 (690d956b): selectSyncCandidates() cross-DB candidate query (error/claimed/stale rows any age; never-seen rows bounded to 30d window); worker scoped, 'Sync now' full; full-scan fallback
- **Where:** `FireflyAutoSyncWorker.kt:74` calls `engine.resyncAll()` with no `scopeDays` → `FireflySyncEngine.kt:154-164` loads the entire transactions table plus a `getTransactionWithSplitsSync` per row (N+1), then the client issues one serial `/search/transactions` call per unsynced item (`FireflyClient.kt:1056`).
- **Why it breaks:** For a user with years of SMS data this is thousands of HTTP requests per periodic run — battery drain and direct amplification of H1's rate-limit/duplicate risk. The `scopeDays` parameter exists but the worker never uses it.
- **Fix:** Scope resyncs to a recent window; batch/paginate search; parallelize within a safe concurrency limit.

### M5. ✅ FIXED 2026-11-13 (8db6f3c4): real pagination via meta.pagination loop (limit=100, links.next fallback) on accounts/categories/budgets/budget-limits + search lookups — pattern ported from waterfly-iii; piggy-banks/recurrences still single-call
- **Where:** `FireflyClient.kt:346` (categories?limit=1000), `:477` (budgets?limit=1000), `:520` (budget-limits?limit=1000) — Firefly III caps `limit` at 100 and returns page 1 only, so users with >100 categories/budgets get silently truncated lists; `syncCategories` then tries to create "missing" categories that actually exist → name-collision failures counted as failed. `getAccounts` (`:314`) has no limit param at all (default page ~50), truncating the account-mapping UI for large instances.
- **Fix:** Implement real pagination loops (follow `next`/increment `page`) on categories, budgets, budget-limits, and accounts.

### M6. ✅ FIXED 2026-11-13 (8db6f3c4): real v6 flow POST /attachments {attachable_type: transaction_journal, attachable_id: journal} → data.attributes.upload_url → streamed octet-stream POST — verified against waterfly-iii's spec-generated client
- **Where:** `FireflyClient.kt:1318-1333` — step 2 POSTs multipart form data to `/api/v1/attachments/{id}/upload`. The documented v6 flow is `POST /api/v1/attachments` (returns `data` with a `temp_url`) followed by a `PUT` of the raw bytes to that `temp_url` — there is no `/attachments/{id}/upload` route in the v6 API, so every receipt upload 404s. It's best-effort (logged only), but receipts therefore never actually upload; users see success.
- **Fix:** Implement the two-step create→PUT-to-temp_url flow, or drop the feature until it works.

---

## Security & hygiene

### S1. 🔒 ✅ FIXED 2026-11-13 (5fd922e1): plaintext PAT removed from DataStore post-migration, idempotent residue cleanup, gated on secure storage availability
- **Where:** `FireflyPreferencesRepository.kt:106-110` — `ensureLegacyMigration()` reads the legacy plaintext token (`appPrefs[stringPreferencesKey("firefly_access_token")]`, `:106`) and copies it into EncryptedSharedPreferences via `saveCredentials` (`:109`), but no code path removes that key from `context.dataStore`. `rg` confirms `firefly_access_token` is referenced only for read (here) and as a const in `FireflyTokenManager`.
- **Why it matters:** An upgrading user's PAT persists at rest in cleartext in the app DataStore, undercutting the EncryptedSharedPreferences posture.
- **Fix:** After successful copy to secure storage, remove the legacy key (`appPrefs.edit { it.remove(...) }`).

### S2. 🔒 ✅ FIXED 2026-11-13 (5fd922e1): plaintext fallback store deleted; session-only in-memory on keystore failure; SECURE_STORAGE_UNAVAILABLE surfaced as settings card
- **Where:** `FireflyTokenManager.kt:46-48` — the `encryptedPrefs` lazy block wraps `EncryptedSharedPreferences.create` in try/catch and, on any `Exception`, returns `context.getSharedPreferences("firefly_secure_prefs_fallback", MODE_PRIVATE)`. On devices where the Android Keystore/MasterKey path fails (some OEM builds), the PAT is written to a plain plaintext prefs file with no warning.
- **Fix:** At minimum warn via `DiagnosticLogger` and surface a "secure storage unavailable" state; consider refusing to persist in cleartext rather than degrading silently.

### S3. 🔒 ✅ FIXED 2026-11-13 (690d956b): single scrubbed DiagnosticEntry is the sole render source for logcat+disk; scrubbing extended (PAT names, JSON token fields, query params, URL userinfo)
- **Where:** `DiagnosticLogger.kt:75, 80-82` — `log()` computes `safeMessage = scrubSecrets(message)` (`:61`) and stores that in the entry, but builds `combinedMessage` from the ORIGINAL message (`append(message)`, `:75`) and passes the ORIGINAL throwable to `Log.e/w/i` (`:80-82`).
- **Status:** Current callers (`FireflyClient`) pass status codes / `body.take(200)` / category names, not the token — **no active leak**. But the class's own contract ("tokens must NEVER be passed here", `:27-28`) relies on caller discipline; one future call that interpolates a URL-with-token or an error echoing the PAT would land in logcat.
- **Fix:** Use `safeMessage` (and a scrubbed stack string) when building `combinedMessage` for `Log.*`, so disk and logcat are scrubbed identically.

### S4. 🔒 ✅ FIXED 2026-11-13 (690d956b): persistent insecureBaseUrlWarningFlow + 'Insecure Firefly URL' settings card (warn, don't block)
- **Where:** `FireflyClient.kt:1566-1570, 279` — `normalizeBaseUrl` only prepends `https://` when NO scheme is present; an explicit `http://` passes through unchanged. Every request then sends `Authorization: Bearer $accessToken` over plaintext. No validation, warning, or LAN-only guard in `FireflyClient` or `FireflySettingsScreen`.
- **Fix:** Warn (or block) when the scheme is http and the host is not a private/LAN address; or require https for non-LAN hosts.

### S5. ⚙️ ✅ FIXED 2026-11-13 (690d956b): UNLIMITED Channel + single IO consumer; log() never blocks/throws; deterministic flush
- **Where:** `DiagnosticLogger.kt:85-87` — `log()` ends with `runBlocking { appendEntry(entry) }`, and `appendEntry` does `withContext(Dispatchers.IO){ currentLogFile.appendText(...) }` under a Mutex. `init` also does `runBlocking{ loadFromDisk() }`. `FireflyFailedSyncsViewModel` calls `diagnosticLogger.d/w/e` from `viewModelScope` coroutines (main dispatcher), so each retry success/failure/exception blocks main.
- **Fix:** Make `log()` non-blocking (launch on IO / enqueue to a channel) instead of `runBlocking` on the caller's thread.

### S6. ⚙️ ✅ FIXED 2026-11-13 (690d956b): global ignore removed (grep-verified no trust-all code); scoped ignores mandated
- **Where:** `app/lint.xml:26` — `<issue id="TrustAllX509TrustManager" severity="ignore" />` suppresses the lint rule that flags X509 trust managers that accept all certificates. The comment attributes it to transitive libraries, but a project-wide ignore means if any dependency (or future app code) installs a trust-all manager, lint won't flag it. Relevant because the app downloads a ~1.5GB LLM model over HTTPS and syncs financial data.
- **Fix:** Scope the ignore to the specific offending library path, or verify which transitive lib triggers it before blanket-suppressing.

### S7. ⚙️ ✅ FIXED 2026-11-13 (690d956b): dead constant deleted (real schema 67 documented)
- **Where:** `app/src/main/java/com/pennywiseai/tracker/core/Constants.kt:38`. Hardcoded to 2 while the actual Room schema is at 47; no production reference outside `Constants.kt`. Harmless only because nothing reads it.
- **Fix:** Delete it or point it at the real `SCHEMA_VERSION`.

---

## ⚪ LOW / nits (architecture pass)

- **L1.** ✅ FIXED 2026-11-13 (8db6f3c4): payloads now send `apply_rules=false` / `fire_webhooks=false` on push. `FireflyClient.kt:1648-1649` (and `:2067`/`:2646` for recurring): payloads set `apply_rules=true`, `fire_webhooks=true` on every create/update → the user's Firefly rules can rename/recategorize/move the row asynchronously after sync. The next run sees `remote.updatedAt` advanced → PULL writes rule-mutated description/category back into local rows (`FireflyClient.kt:1129`), silently diverging local data from what the user entered.
- **L2.** ✅ FIXED 2026-11-13 (ede3ac8f): pre-HTTP config guard surfaces an actionable mapping/default-asset error; zero HTTP issued. `FireflyClient.kt:1668` and `:1703`: when no default asset or mapping is configured, payloads use literal `'Checking Account'` / `'Savings Account'`. If those accounts don't exist in the user's Firefly, every create/update fails with a 422 until mappings are configured — the failure mode is indistinguishable from other errors and never surfaces "configure your default asset account".
- **L3.** ✅ FIXED 2026-11-13 (ede3ac8f): `ItemResult.httpStatus` propagated; auth detection status-first (401/403), message matching only as fallback. `FireflyClient.kt:1001`: `isAuthError = item.message?.contains("401") == true`. Any 401 whose body/message doesn't contain the literal `"401"` (Ktor exception text, proxy rewriting status) misses the token-expired path; conversely any message containing `"401"` mis-triggers it. The HTTP status is available at the call sites but not propagated into `ItemResult`.
- **L4.** ✅ CLOSED 2026-11-13 (5e703cd5): the category-loss half was **disproven against the official v6 OpenAPI** (`swagger_input/firefly-iii.yaml` — `BudgetProperties` carries no category fields; Firefly budgets are category-agnostic, budget↔category links live only on journals), so `includeAllCategories=true` is the faithful mapping (now documented in code). The period-inference half was fixed: the budget's declared `auto_budget_period` wins when the limit span agrees with it, day-span heuristic otherwise, honest CUSTOMs for quarterly/annual spans. Original finding: `FireflyClient.kt:896-923`: unmapped remote budgets are imported with `includeAllCategories=true` and `categories=emptyList()`, discarding the budget's category links from Firefly; subsequent push/pull of limits then operates on a locally-different budget shape. Also `inferPeriodType` (`:1872-1879`) approximates period type from day counts (6..8=WEEKLY, 28..31=MONTHLY), misclassifying e.g. 4-weekly cycles as CUSTOM.

---

## ✅ Solid — leave alone

- **Money math is safe.** `FireflyClient` uses `java.math.BigDecimal` + `toBigDecimalOrNull()`/`toPlainString()` throughout (e.g. `:539`, `:1428`, `:1620`, `:1635`); the backup `BigDecimalSerializer` emits/reads decimal strings (`BackupSerializers.kt:37-41`). No Double/Float currency arithmetic in the delta.
- **Rebrand is consistent.** All version references use `BuildConfig.VERSION_NAME` (`build.gradle.kts` `versionName=2.0.0`, `versionCode=94`); no lingering hardcoded `"1.3.1"`/`"2.0.0"` strings in `app/src/main`. `applicationId=com.cashiro.cashiro` with `namespace=com.pennywiseai.tracker` is an intentional fork split, not a half-rename.
- **No hardcoded secrets/IPs/credentials** in `app/src/main` or `Constants.kt`; the only external endpoint is the R2 public-bucket model URL (`Constants.kt:89`), which is SHA-256-pinned (`:105`) — supply-chain mitigated.
- **TLS posture.** `FireflyClient` uses default Ktor TLS (no custom trust-all manager in Kyle's code); the token travels only in the `Authorization` header, not request bodies.
- **Sidecar schema** `app/schemas/.../FireflySyncDatabase/1.json` matches the `@Entity` definitions exactly (version 1).
- **Fork-era migration chain is clean.** `MIGRATION_46_47` (`PennyWiseDatabase.kt:481`) is a single valid ALTER; the fork-era chain 47→48 … 51→52 are clean single-column ADDs. (The broken edges are specifically the direct →67 rebuilds in C1.)
- **First-sync idempotency is correct.** `resyncAll` filters sidecar `syncedAt == null` (`FireflySyncEngine.kt:164`) and `markSynced` is an upsert, so commit `4e613d1`'s state persistence makes repeat runs skip already-synced rows.
- **Tests are meaningful, not compile-only.** `FireflySyncDaoTest` (androidTest) asserts round-trip/upsert semantics; `FireflySyncEngineLiveE2eTest` is a real integration test (gated on `FIREFLY_PAT` via `assumeTrue`) that asserts sidecar state persistence, idempotent skip on second run, and pull of remote edits.
- **Post-merge hooks are safely seated.** `AddTransactionUseCase.kt:120-123` and `SmsTransactionProcessor.kt:289-291` wrap `onTransactionSaved` in `runCatching`, so a sync failure can never break the save path; `TransactionRepository.updateTransactionFromFirefly` (`:54`) updates local data only, with state recorded by the caller.
- **API shape is otherwise v6-conformant.** `/api/v1/transactions` POST/PUT with transaction groups + splits, Bearer tokens, `Accept: application/vnd.api+json`, date-only payloads, search via `external_id_is` query (`FireflyClient.kt:94-96`, `:1373-1375`, `:1963`).

---

## Stale premise (no finding)

The earlier "backup JsonElement bridge / removed `@Serializable`" concern (attributed to commit `da2aeb1d`) is **not present in the net delta**. `git diff 6758f607..HEAD` shows zero changes to backup files or `TransactionEntity.kt`, and `transactionToJsonElement`/`jsonElementToTransaction` do not exist anywhere at HEAD. At HEAD `TransactionEntity` is still `@Serializable` (line 16) and `DatabaseSnapshot.transactions` is `List<TransactionEntity>`, serialized via `backupJson` with the contextual BigDecimal→string serializer — so money round-trips exactly and `BackupModelsTest` exercises the real production path. No correctness/precision finding.

---

## Recommended fix order

1. ~~**C1**~~ — **DONE 2026-11-13** (see fix summary above). Follow-up **also done**: `FireflySyncStagingBackfill` (eb447085).
2. ~~**H1 + H2**~~ — **DONE 2026-11-13** (198be74b, eb447085).
3. ~~**H3**~~ — **DONE 2026-11-13** (incl. resyncAll error-row pickup).
   - C1 follow-up **done**: `FireflySyncStagingBackfill` consumes `firefly_sync_state_staging` → sidecar, drops it (eb447085).
   - Pending for CI: run `gradlew test` (no JDK on build host); consider adding `kotlinx-coroutines-test` dep; optionally pass the 9th ctor arg explicitly in `FireflySyncEngineLiveE2eTest` instead of relying on the nullable default.
4. **S1 + S2** — remove the legacy plaintext PAT key; warn (or refuse) on unencrypted-storage fallback.
5. **M1–M6**, then LOW/nits, in priority order.

---

*Static review only; no builds run. Room migration-path behavior verified against room-runtime/room-common 2.6.1 sources. Firefly III v6 attachment-upload semantics checked against public API docs (POST `/attachments` → `temp_url`; PUT to `temp_url`). Findings marked [verified] were independently re-confirmed against the working tree.*

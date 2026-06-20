# Changelog

All notable changes to **PennywiseAI-Firefly** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.3.1] - 2026-06-20

### Fixed
- **GitHub Actions CI parser tests now pass** (`:parser-core:jvmTest`).
  - SliceParserTest: "Slice UPI transfer (sent to)" case corrected from `CREDIT` to `EXPENSE`. This matches the updated `SliceParser.extractTransactionType()` logic (post-RBI PPI changes: "sent" UPI transfers from the bank account are regular `EXPENSE`; `CREDIT` type is reserved for explicit credit-card context only).
- Re-audit of all parser test expectations after the large bank parser expansion.

### UI & Polish (v1.3.1)
- Reorganized Firefly Settings page for much better UX/flow:
  - Clear section headers: Sync Actions, Connection, Account Mappings, Category Mappings.
  - Grouped sync buttons (quick vs bulk) with results now properly placed below.
  - Added loading "Saving..." state on Save Settings.
  - Consistent text colors: onSurface (white in dark) for labels, onSurfaceVariant for secondary.
- Dark mode fixes across Firefly pages: explicit theme colors so text renders correctly (white/light).
- Fixed strange characters for hidden balances: switched •••• masks to reliable **** (in account displays used by Firefly mappings).
- Cleaned compiler warnings: removed unnecessary non-null assertions (!! ) and migrated deprecated rememberSwipeToDismissBoxState(confirmValueChange) to modern snapshotFlow + LaunchedEffect pattern in multiple screens.
- Minor: better empty states, notes, and consistency in Firefly UI.

### Release Summary (full changelog highlights)
This release consolidates major work to make PennywiseAI-Firefly stable and CI-clean:

**Parser improvements (major upstream port, fully Firefly-compatible)**
- Many new / improved bank parsers added with high accuracy:
  - Africa: M-Pesa Mozambique (content-aware), CRDB (Tanzania, multi-currency), eMola, Millennium BIM, Standard Bank Mozambique, Telebirr, Zemen, Dashen.
  - Middle East / Iran: Emirates Islamic, Bankino, blu Bank, Mellat, Melli, Parsian.
  - Nigeria: Access, Zenith, Keystone, Jaiz, Opay.
  - India: Navi Mutual Fund, various accuracy fixes (IndusInd, Slice, etc.).
  - Others: Thailand banks, more Nepal, Pakistan, etc.
- Content-aware parser dispatch (`getParsers` + `parse`) for shared senders (e.g. M-Pesa variants).
- All new parsers produce standard output (including `transactionHash` for Firefly external IDs).

**Firefly III Integration (core feature of this fork)**
- Stable hash-based `external_id` (`pennywise-{transactionHash}`) — survives reinstalls and re-parsing.
- One-time migration + reconcile on first enable.
- Multiple sync modes: Sync Last 30 Days, Sync All Unsynced, Full Sync, auto periodic sync (configurable interval).
- Account + category mappings (live accounts fetched from Firefly).
- Per-transaction sync status + manual "Sync to Firefly" / "Resync" on detail screen.
- Failed syncs queue + retry UI.
- Optional raw SMS in notes; "Synced from PennyWise" footer.
- Pre-existence check before POST to avoid duplicates.

**Bloat removal & personal use focus**
- Complete removal of Pro tier / Google Play Billing layer (no paywall, no billing code, no Google connections).
- All previously paid features (unlimited rules, imports, exports, account merge, etc.) are now always unlocked.

**Build & reliability fixes**
- Fixed transient IR/KSP backend error when using kotlinx.serialization directly on Room entities (TransactionEntity + backup path now uses plain entities + JsonElement bridge for serialization).
- Consistent external ID handling across auto-sync, manual sync, and add-transaction paths.
- GitHub Actions workflow verified (parser tests + clean compile + unit tests).
- Full code recheck for errors, deprecations, and compatibility.

**Other notes**
- This is a vibe-coded personal fork. Probably shouldn't be used by anyone in production with real money without thorough review.
- See README for Firefly setup and known limitations.

## [1.3.0] - 2026-06-20

### Fixed
- Transient IR / KSP backend compiler error on TransactionEntity (and backup kotlinx port). Removed all `@Serializable` + `@Contextual` annotations from the Room entity (pure data class now). Updated backup path to store/retrieve transactions as `List<JsonElement>` in `DatabaseSnapshot` with `transactionToJsonElement` / `jsonElementToTransaction` helpers (camelCase keys for compatibility). Firefly sync fields, hash external IDs, and all legacy behaviour preserved.
- Inconsistent Firefly external ID storage in auto-sync paths (AddTransactionUseCase, FireflyAutoSyncWorker, syncLast30Days) — now consistently compute + store the stable `pennywise-{hash}` external ID on success.
- Full code recheck for errors after Pro removal + ports: no lingering billing/paywall references in executable code; all features unlocked; clean build verified.

## [1.2.0] - 2026-06-20

### Changed
- Removed the entire Pro tier billing layer infrastructure (billing package, gateways, products, modules, dependencies) to eliminate bloat and prevent any Google Play Billing or Google connections.
- Removed the paywall UI entirely (UpgradeSheet, ViewModels, etc.).
- All extra "Pro" features (unlimited custom rules, unlimited PDF statement imports, unlimited CSV exports, account merge, etc.) are now always available without any gating or billing.
- Retained only the minimal always-unlocked stub approach (F-Droid style) so features work for personal use.
- Cleaned references in ViewModels and screens (ManageAccounts, Rules, Export, Import, Settings).
- Full compatibility with existing Firefly III integration (hash external IDs, sync, mappings, etc.) preserved. No changes to Firefly-related code.

## [1.1.0] - 2026-06-20

### Added (major upstream ports, Firefly-compatible)
- Bank parser expansion and accuracy improvements:
  - New parsers: M-Pesa Mozambique (content-aware with Portuguese "Confirmado" + MT), Access/Keystone/Zenith/Jaiz/Opay (Nigeria), Bankino + blu Bank (Iran/Persian), CRDB (Tanzania, multi-currency + TZS), Emirates Islamic (UAE, multi-currency cases), eMola + Millennium BIM (Mozambique), Mellat Bank (Iran), Navi Mutual Fund (AMC SIP/unit allotment), and supporting accuracy fixes for IndusInd (credit card, merchant boundary), Slice (UPI classification), CRDB (keyword tiering and income precedence), Opay/Zenith (merchant anchoring), etc.
  - Content-aware parser dispatch (`getParsers`) for senders shared across regions (e.g. M-Pesa variants).
- Currency support: Brazilian Real (BRL) + related locale/symbol handling.
- Account improvements:
  - Per-account currency selection (including NGN and others).
  - Main account picker now drives the app's default/base currency (with explicit Settings selector override via user-set flag; main account changes apply without overriding explicit choice).
  - Account aliases, manual/cash balance derivation from transactions (incl. transfers), atomic manual edits, SMS accounts no longer reclassified as manual, account merge (core logic), bulk profile apply to transactions, account filtering/grouping in Transactions & Analytics lists, Balance History promoted to dedicated page.
- Budgets: Type-aware budgets — track spending against a transaction type (EXPENSE/INCOME/etc.) in addition to (or instead of) category; per-type current spending shown on edit; widget savings delta includes type buckets.
- Analytics: Per-transaction "Exclude from analytics" toggle (stored in DB, respected in spending totals/trends/categories while still counting for balances/history); "Excluded" tag displayed on rows; account scoping (exclude hidden accounts + profile filter).
- Transactions / Loans / UI polish:
  - Bulk-edit selection mode on Transactions list with multi-select actions.
  - Self-transfer detection, suggestion, one-tap convert to TRANSFER with proper from/toAccount, directional subtitles.
  - Duplicate transaction as pre-filled template.
  - Loan fixes: scrollable Mark-as-loan sheet, deletion gated on remaining principal, allow unmarking as loan, stale loan state clearing, principal correction on multi-entry unlinks.
  - UI/UX: transaction actions moved to overflow menu, exclude toggle repositioned, top-bar icon overlap fixes, various polish (headers, gestures, recurring indicators).

All ported features were verified compatible with PennywiseAI-Firefly's Firefly III integration:
- New parsers continue to produce standard ParsedTransaction → TransactionEntity (including our transactionHash used for stable `external_id`, currency, from/toAccount, excludedFromAnalytics, firefly* fields).
- Firefly sync (pre-check by external_id, bulk/full sync, auto worker, Settings mappings, detail-screen retry/resync status, hash migration + reconcile) unaffected and continues to work.
- Exclude flag treated as analytics-only (synced transactions still go to Firefly).
- Account currency and manual vs SMS distinctions respected for mappings and payloads.

### Ported from remaining "other" list (#11,13,14,15,16,18,20)
- #11: DB infra - single shared MIGRATION list (Firefly columns unaffected).
- #13: Notif 'More categories' picker.
- #14: Credit card outstanding (not limit) in carousel.
- #15: Prefer description over merchant as row title.
- #16: Rule icon refresh + bg fixes.
- #18: Parser unused import cleanup.
- #20: Small polish (rules, empty account, redundant).
All confirmed no impact on Firefly sync paths.

### Added (additional upstream #1,2,4,5,6,7,8,9)
- #1 Smart Rules info card dark mode legibility.
- #2 Full subscriptions/recurring (autopay, link tx, mark paid, sort) - txs created use hash path for Firefly.
- #4 Backup resilience (lists for subs/loans/groups; firefly fields roundtrip via entity).
- #5 Account merge (core + sheet) - note: changes account key for txs (affects future mappings); existing fireflyExternalId preserved.
- #6 Self-transfer refinements (suggest, convert, hints) - TRANSFER handled in Firefly payload.
- #7 Duplicate tx as template - new tx fresh hash, not auto-synced.
- #8 Smart rules edit/duplicate + account + notif category picker.
- #9 Home 'This Month' cash-flow card with channel breakdown (UI only).

### Changed
- Version bumped for this upstream sync release.

---

## [Unreleased]

### Added
- Configurable automatic Firefly sync interval (daily/weekly/never) with WorkManager scheduling
- "Sync All Unsynced" and "Full Sync Everything" bulk sync functions in Firefly settings
- Legacy Firefly external ID migration to stable transaction hash-based IDs (`pennywise-{hash}`)
- One-time migration + reconcile automatically run on first enabling Firefly after fresh install/reinstall
- "Reconcile with Firefly" button for manual post-reinstall recovery
- Pre-flight existence check before syncing (using external_id) to prevent duplicates
- Migration/reconcile completion logged to syncResult (visible as toast-like message in settings)

### Changed
- Firefly `external_id` now consistently uses stable `transactionHash` (instead of DB `id`) for reinstall resilience and chronological ordering via SMS timestamp (`dateTime`)
- Local `fireflyExternalId` storage strategy updated to hash-based value
- Reconcile logic tries both hash-based and legacy external IDs for matching
- Bulk sync and auto-sync now benefit from hash-based dedup and pre-checks
- SMS received timestamp (via `dateTime`) confirmed as the transaction occurrence date for Firefly syncs (helps chronological order); `createdAt` remains app record time

### Fixed/Improved
- Transactions existing only in Firefly (e.g. pre-SMS era or manual older entries without Pennywise counterpart) are **unaffected** — logic only operates on local Pennywise transactions and matches via external_id; no deletions or modifications to Firefly-only data
- Reinstall scenario: re-parsed SMS use same hash → same external_id → reconcile/full sync can match and mark without creating dups

---

## [1.0.0] - 2026-05-30

### Added
- Full Firefly III integration (major new feature of this fork)
  - Automatic sync of parsed SMS transactions to self-hosted Firefly III
  - Support for syncing manually added transactions
  - Per-account mapping (PennyWise account → Firefly asset account)
  - Per-category mapping (PennyWise category → Firefly category)
  - Option to hide raw SMS text from Firefly notes
  - Secure token storage using `EncryptedSharedPreferences`
  - "Test Connection" functionality
  - "Send Test Transaction" button
  - "Sync Last 30 Days" for historical data
  - Dedicated "Failed Syncs" screen with individual and bulk retry
  - Firefly sync status visible on each transaction's detail screen (with retry)
- New full-screen Firefly III settings (moved out of dialog)
- `FireflyTokenManager` for secure credential handling

### Changed
- App renamed to **PennywiseAI-Firefly**
- `applicationId` and `namespace` changed to `com.thenoticingbegins.pennywiseai.firefly`
- Removed automatic reporting of unrecognized SMS (prevents users from reporting issues to the original project)
- Updated all external links to point to this fork

### Removed
- Reporting functionality that previously sent data to the original author's services

### Credits
This is a fork of [PennyWise AI](https://github.com/sarim2000/pennywiseai-tracker) by sarim2000.  
All core SMS parsing, AI, budgeting, and UI features come from the original project.

---

## Previous Versions

This fork starts its own versioning at **1.0.0**.

For the history of the original PennyWise AI project, see:
https://github.com/sarim2000/pennywiseai-tracker/releases
# Changelog

All notable changes to **PennywiseAI-Firefly** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

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
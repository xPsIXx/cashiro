# Upstream sync recipe (Cashiro → pennywiseai-tracker)

Cashiro = upstream PennyWise AI + the Cashiro Firefly add-on. `main` is kept in line
with `upstream/main` using **merge commits only — never rebase**. This document is the
rulebook for that merge; read it before touching anything Firefly-related or any file
listed in the seam inventory below.

## The recipe (every upstream update)

```bash
git fetch upstream
git merge upstream/main            # merge commit; never rebase
# …resolve conflicts per the classes below…
bash ./gradlew :app:assembleStandardDebug   # (Windows: ./gradlew.bat)
bash ./gradlew :app:testStandardDebugUnitTest
git commit
```

## Conflict classes

| File class | Take |
| --- | --- |
| **Add-on-owned** — `app/src/main/java/com/pennywiseai/tracker/data/firefly/*`, `worker/FireflyAutoSyncWorker.kt`, `worker/FireflyRetryWorker.kt`, `ui/screens/settings/FireflySettingsScreen.kt`, `FireflySettingsViewModel.kt`, `FireflyFailedSyncsScreen.kt`, `FireflyFailedSyncsViewModel.kt`, `data/diagnostic/DiagnosticLogger.kt` | **OURS** wholesale (`git checkout --ours`) |
| **Seam files** — app entities/DAOs/repos/prefs/backup (see inventory below) | upstream, then **re-verify our deletion still holds** (our side is deletions only, so upstream additions merge; take THEIRS where both touched) |
| **Hooks** — `AddTransactionUseCase`, `SmsTransactionProcessor`, `TransactionDetailViewModel/Screen`, `SettingsScreen/SettingsViewModel`, `PennyWiseNavHost`, `MainScreen`, `strings.xml`, `core/Constants.kt` | keep THEIR new code, **re-apply our hook lines** (the `fireflySyncEngine.…` calls / routes / row), re-verify by the grep gate below |
| **DB schema** — `PennyWiseDatabase.kt`, `app/schemas/…/PennyWiseDatabase/*.json` | upstream's `SCHEMA_VERSION` + JSONs wholesale; **never bump our sidecar DB** (`FireflySyncDatabase`, version 1) — firefly state is add-on-owned |
| **Parser-core / shared / everything else** | upstream wholesale |

## Fixed seam inventory

### Seam files (upstream files Cashiro edits — keep the edits minimal)

- `data/database/entity/` TransactionEntity, BudgetEntity, SubscriptionEntity — no firefly columns (deleted once).
- `data/database/dao/` TransactionDao (`updateTransactionFromFirefly`), BudgetDao (`getAllBudgetsList`, `updateBudgetFromSync`), SubscriptionDao (`updateSubscriptionFromSync`) — add-on local-data methods.
- `data/database/PennyWiseDatabase.kt` — transitional migrations `MIGRATION_47_67 … MIGRATION_52_67` (fork-era installs → upstream schema 67+); registered in `ALL_MIGRATIONS`.
- `data/preferences/UserPreferencesRepository.kt` — `internal val Context.dataStore` (legacy prefs read by the add-on).
- `data/backup/BackupSerializers.kt` — no firefly fields (sync state is not backed up).
- `data/repository/` Transaction/Budget/SubscriptionRepository — `updateTransactionFromFirefly`, `updateBudgetFromSync`, `updateSubscriptionFromSync`.
- `gradle/libs.versions.toml` + `gradle/wrapper/gradle-wrapper.properties` + `app/build.gradle.kts` — toolchain pins (AGP 9.2.x, Gradle 9.7.x, Kotlin 2.3.x, KSP 2.3.9, Hilt 2.59.2, litertlm pinned: `0.17+` ships Kotlin 2.4 metadata that Hilt/Room cannot read yet) + `security-crypto` (add-on token store; upstream dropped it).

### Hook lines (re-apply when upstream rewrites these files)

- `AddTransactionUseCase.execute` and `executeTransfer`: after insert, `runCatching { fireflySyncEngine.onTransactionSaved(transaction.copy(id = transactionId)) }`.
- `SmsTransactionProcessor.saveParsedTransaction`: after balance/widget work, same engine call with `finalEntity.copy(id = rowId)`.
- `TransactionDetailViewModel`: ctor param `fireflySyncEngine`; `fireflyState` flow + `syncToFirefly()`.
- `TransactionDetailScreen`: `fireflySync` slot threaded `TransactionDetailContent → TransactionReceipt` rendering `FireflySyncStatusSection(viewModel)`.
- `SettingsScreen`: “Firefly III Sync” row (Data Management) + `onNavigateToFireflySettings`/`onNavigateToFireflyFailedSyncs` params.
- `PennyWiseNavHost`: `FireflySettings` + `FireflyFailedSyncs` composable routes.
- `core/Constants.kt`: `GITHUB_URL = https://github.com/xPsIXx/cashiro`.

### Grep gate (after every merge)

```bash
# Every file referencing firefly outside the add-on packages:
grep -rli firefly app/src/main/java | sort
```
Expected: the seam/hook files above, `data/diagnostic/DiagnosticLogger.kt`, nav
(`PennyWiseDestinations.kt`, `PennyWiseNavHost.kt`, `MainScreen.kt`), and nothing else.
Entity/DAO/prefs/backup files must show **no** `firefly` references.

## DB transitions (one-time, already merged)

Installs that upgraded through the firefly era (our fork schema versions 47–52) reach the
consolidated schema via `MIGRATION_{47..52}_67`: they drop the `firefly_*` columns (sync
state lives in `cashiro_firefly.db`) and apply upstream's 48→67 additions. Upstream schema
versions now start at 67+; add new transitional `Migration(v, U)` only if a new fork-only
schema bump ever ships (it shouldn't — **the app schema is upstream-owned now**).

## Notes

- **Never bump `FireflySyncDatabase`/`cashiro_firefly.db`** along with upstream DB bumps —
  that's the whole point of the sidecar.
- Firefly state is not part of backups; after restore the engine re-derives
  `pennywise-{transactionHash}` external ids and updates instead of duplicating.
- If an upstream commit moves a hook’s natural resting place (e.g. a new use case owns
  transaction creation), move the hook to the new creation path and update this document.

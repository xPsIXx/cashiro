# Changelog

All notable changes to **PennywiseAI-Firefly** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
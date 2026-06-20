# PennywiseAI-Firefly

[![GitHub Release](https://img.shields.io/github/v/release/TheNoticingBegins/pennywiseai-Firefly)](https://github.com/TheNoticingBegins/pennywiseai-Firefly/releases)
[![GitHub stars](https://img.shields.io/github/stars/TheNoticingBegins/pennywiseai-Firefly?style=social)](https://github.com/TheNoticingBegins/pennywiseai-Firefly)

**A fork of [PennyWise AI](https://github.com/sarim2000/pennywiseai-tracker) with Firefly III integration.**

This fork adds automatic syncing of transactions to your self-hosted [Firefly III](https://www.firefly-iii.org/) instance, along with account & category mapping.

> **Note**: This is a personal fork. The main development happens in the [original repository](https://github.com/sarim2000/pennywiseai-tracker).

## What’s New in This Fork

- Automatic sync of SMS transactions to Firefly III
- Support for manually added transactions
- Account and category mapping (with real accounts fetched from Firefly for selection)
- Option to hide raw SMS text in Firefly notes
- Secure token storage
- Tools: Send Test Transaction, Sync Last 30 Days, Sync All Unsynced, Full Sync Everything, Failed Sync retry screen
- Configurable auto-sync (daily/weekly/never) via WorkManager
- Firefly sync status visible on transaction details (with manual Sync/Resync)
- Reconcile with Firefly button (for post-reinstall recovery)

### Resiliency & Reinstall Features
- Stable hash-based `external_id` (`pennywise-{transactionHash}`) instead of volatile DB IDs
- One-time legacy ID migration + automatic reconcile when first enabling Firefly after a fresh install/reinstall
- Pre-sync existence check in Firefly to avoid duplicate entries
- Sync status survives reinstalls (re-parsed SMSs match via content hash)
- Older Firefly-only transactions (e.g. pre-SMS era or manual entries without PennyWise counterpart) are completely unaffected
- SMS received timestamps used for transaction `dateTime` (ensures proper chronological order when pushed to Firefly)

**Full release notes and changelog**: See [CHANGELOG.md](CHANGELOG.md) and [releases](https://github.com/TheNoticingBegins/pennywiseai-Firefly/releases)

**Full release notes**: [v1.0.0](https://github.com/TheNoticingBegins/pennywiseai-Firefly/releases/tag/v1.0.0)

## Installation

```bash
git clone https://github.com/TheNoticingBegins/pennywiseai-Firefly.git
cd pennywiseai-Firefly
./gradlew assembleDebug
```

Install the APK:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

This fork is **not** available on the Play Store or F-Droid.

## Links

- **This Fork**: https://github.com/TheNoticingBegins/pennywiseai-Firefly
- **Original Project**: https://github.com/sarim2000/pennywiseai-tracker
- **Firefly III**: https://www.firefly-iii.org/

## License

[AGPL v3](LICENSE) — same as the original project.

---

**Made for people who want both PennyWise and Firefly III.**  
If you find this useful, consider starring the repository.
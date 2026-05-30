# PennywiseAI-Firefly v1.0.0

**First official release of this fork with deep Firefly III integration.**

This is a personal fork of the excellent [PennyWise AI](https://github.com/sarim2000/pennywiseai-tracker) project, focused on users who want to automatically sync their transactions to a self-hosted [Firefly III](https://www.firefly-iii.org/) instance.

---

## 🚀 Highlights

- **Full Firefly III Integration** — Automatically push parsed SMS transactions (and manual entries) to your own Firefly III server.
- **Smart Mapping** — Map individual accounts and categories from PennyWise to Firefly.
- **Privacy & Control** — Option to exclude raw SMS text from Firefly notes.
- **Secure by Design** — Personal Access Tokens are now stored using Android’s EncryptedSharedPreferences.
- **Manual Controls** — Send test transactions, sync the last 30 days, or retry failed syncs.
- **Visibility** — See Firefly sync status directly on each transaction.

---

## ✨ New Features

### Firefly III Sync
- Automatic background sync of new SMS transactions
- Support for manually added transactions
- Per-account and per-category mapping
- Toggle to hide raw SMS content in Firefly notes
- "Send Test Transaction" button
- "Sync Last 30 Days" for historical data
- Dedicated **Failed Syncs** screen with retry options
- Firefly sync status shown on Transaction Detail screen

### Fork Improvements
- App renamed to **PennywiseAI-Firefly**
- Package renamed (`com.thenoticingbegins.pennywiseai.firefly`)
- Removed automatic reporting of unrecognized SMS to the original project
- Updated all external links to point to this fork

---

## 📥 Installation

### From Source (Recommended)

```bash
git clone https://github.com/TheNoticingBegins/pennywiseai-Firefly.git
cd pennywiseai-Firefly
./gradlew assembleDebug
```

Install the APK:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Building a Release APK
```bash
./gradlew assembleRelease
```

---

## ⚠️ Important Notes

- This is a **personal fork** and is **not** affiliated with the original PennyWise AI project.
- This version is **not** available on Google Play or F-Droid.
- Firefly III sync is **completely optional** and disabled by default.
- You need your own self-hosted Firefly III instance + a Personal Access Token.

---

## 🙏 Credits

This project is a fork of **[PennyWise AI](https://github.com/sarim2000/pennywiseai-tracker)** by [sarim2000](https://github.com/sarim2000).

All core SMS parsing, on-device AI, budgeting, analytics, and UI work comes from the original project.  
The Firefly III integration and fork-specific changes were developed in this repository.

---

## 🔗 Links

- **GitHub**: https://github.com/TheNoticingBegins/pennywiseai-Firefly
- **Original Project**: https://github.com/sarim2000/pennywiseai-tracker
- **Firefly III**: https://www.firefly-iii.org/

---

**Thank you** to everyone who contributed to the original PennyWise AI project. This fork exists because of your work.

If you find this Firefly integration useful, consider starring the repository! ⭐
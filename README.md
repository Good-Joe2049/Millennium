<a id="top"></a>

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" height="128" alt="Millennium logo">
</p>

<h1 align="center">Millennium</h1>

<p align="center">
  <strong>SteamDB insights, Steam Guard export, and maFile restoration — right inside Steam.</strong><br>
  An Android Steam enhancement module powered by modern libxposed.
</p>

<p align="center">
  <a href="README_zh.md">简体中文</a> •
  <a href="#features">Features</a> •
  <a href="#steam-guard">Steam Guard</a> •
  <a href="#mafile">maFile &amp; Restoration</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="https://github.com/Good-Joe2049/Millennium/issues">Feedback</a>
</p>

<p align="center">
  <a href="https://github.com/Good-Joe2049/Millennium/releases/latest"><img src="https://img.shields.io/github/v/release/Good-Joe2049/Millennium?style=flat-square&amp;label=release&amp;labelColor=334155&amp;color=3B82F6" height="20" alt="Latest release — download APKs"></a>
  <a href="https://github.com/Good-Joe2049/Millennium/releases"><img src="https://img.shields.io/github/downloads/Good-Joe2049/Millennium/total?style=flat-square&amp;label=downloads&amp;labelColor=334155&amp;color=0D9488" height="20" alt="Total release asset downloads"></a>
  <a href="https://github.com/Good-Joe2049/Millennium/commits/main/"><img src="https://img.shields.io/github/last-commit/Good-Joe2049/Millennium/main?style=flat-square&amp;label=last%20commit&amp;labelColor=334155&amp;color=8B5CF6" height="20" alt="Last commit on main"></a>
  <a href="https://t.me/MillenniumXposed"><img src="https://img.shields.io/badge/Telegram-229ED9?style=flat-square&amp;logo=telegram&amp;logoColor=white" height="20" alt="Millennium Telegram channel"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-16A34A?style=flat-square&amp;labelColor=334155&amp;logo=android&amp;logoColor=white" height="20" alt="Android 8.0 or later">
  <img src="https://img.shields.io/badge/libxposed-API%20102-6366F1?style=flat-square&amp;labelColor=334155" height="20" alt="libxposed API 102">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-64748B?style=flat-square&amp;labelColor=334155" height="20" alt="arm64-v8a">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-0F766E?style=flat-square&amp;labelColor=334155" height="20" alt="MIT License"></a>
</p>

<table align="center">
  <tr>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/panel-witcher3.jpg"><img src="docs/images/panel-witcher3.jpg" width="280" alt="SteamDB panel on The Witcher 3 store page"></a><br>
      <sub>SteamDB panel</sub>
    </td>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/steam-menu-preview.jpg"><img src="docs/images/steam-menu-preview.jpg" width="280" alt="Millennium entry in the Steam menu"></a><br>
      <sub>Steam menu entry</sub>
    </td>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/module-settings-preview.jpg"><img src="docs/images/module-settings-preview.jpg" width="280" alt="Millennium General page with the Liquid Glass navigation bar"></a><br>
      <sub>Module settings</sub>
    </td>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/steam-guard-export-preview.jpg"><img src="docs/images/steam-guard-export-preview.jpg" width="280" alt="Steam Guard export dialog with sensitive data obscured"></a><br>
      <sub>Steam Guard export</sub>
    </td>
  </tr>
</table>

---

**Millennium** brings game statistics, price history, authenticator export, and maFile backup and restoration to the Android Steam app. Open a floating SteamDB panel while browsing a game's store page, or use the **Millennium** entry above **Store** in Steam's menu to manage the module.

**Verified in v1.1.5:** importing a maFile onto another phone already signed into the same account restores Steam Guard codes and login approvals in the official Steam app. Both phones can generate matching codes.

<a id="features"></a>

## ✨ Features

| Feature | What it offers |
| :--- | :--- |
| **SteamDB floating panel** | Current players, 24-hour and all-time peaks, followers, historical lows, SteamDB rating, and the last update date. |
| **Price history** | Prices for the store's currency and pricing region, with discounts, recorded occurrences, and dates where available. Includes the 2-year low when supplied by SteamDB. |
| **Floating controls** | Drag the floating button, snap it to an edge, and retain its position. Refresh the current game's data or open its SteamDB page in a browser. |
| **In-app settings** | A full-screen miuix interface with General, Themes, Logs, and Settings tabs, swipe navigation, and animated transitions. |
| **Liquid Glass navigation** | An optional glass navigation bar with a draggable selection capsule, spring motion, and optical effects. Requires Android 13 or later. |
| **Steam Guard export** | View the authenticator type, username, issuer, and secret. Tap a value to copy it, or select an account when multiple accounts are available. |
| **QR codes and direct import** | Switch between Stratum and Aegis formats, with a morphing QR transition, or open the selected authenticator directly with the account details. |
| **maFile import and export** | Back up an account, import a compatible maFile, preview its fields, and re-export it. Values appear directly; tap to copy the full value even when the display is shortened. |
| **Steam Guard and login restoration** | Restore authenticator data and usable login credentials on the next full Steam startup. Match accounts by SteamID, replace the imported account's data, and retain other account records. |
| **Steam management** | Authorize Shizuku/Sui to uninstall Steam while keeping its local app data for the current Android user. |
| **Module language** | Follow System, English, and 中文（简体）. Changes apply immediately and the selection is saved. |

<a id="interface"></a>

## 📱 Interface & interaction

The module settings use [miuix](https://github.com/compose-miuix-ui/miuix) components for cards, switches, dialogs, and selection menus. You can tap the bottom tabs or swipe between pages.

Enable **Liquid Glass navigation bar** under **General → Appearance** to apply the glass effect to the module's bottom bar. The capsule can be dragged between tabs; Steam's own navigation bar is unaffected.

The **SteamDB floating panel** switch takes effect immediately. Language selection is available under **Settings → Language** and changes the module's interface without changing Steam's language.

**Themes** and **Logs** are currently placeholders. Themes is reserved for future **Steam themes**. Configuration backup and restore, and selectable dark, light, and Monet themes for the module interface, are also planned.

<a id="steam-guard"></a>

## 🔐 Steam Guard export

Open **Steam → Menu → Millennium → General → Export Steam Guard**.

- **Tap to copy:** each displayed value has its own press feedback and clipboard confirmation.
- **Scan a QR code:** select Stratum or Aegis to show the corresponding import format. Only one QR code is displayed at a time.
- **Add directly:** tap **Add to Stratum** or **Add to Aegis** to open that app with the correct link. No manual secret entry or QR scanning is needed.

| Authenticator | Expected direct-import flow |
| :--- | :--- |
| [Stratum](https://github.com/stratumauth/app) | Adds the Steam authenticator after opening and unlocking the app, if required. |
| [Aegis](https://github.com/beemdevelopment/aegis) | Opens a prefilled entry for you to review and save. Unlock the app first if prompted. |

The two apps use different Steam link formats; Millennium selects the matching format for both QR codes and direct import. Install and initialize the chosen authenticator before using direct import. Its exact confirmation flow can vary by version.

QR codes are generated on the device. The module no longer copies authenticator data to the clipboard automatically when Steam starts. If no data is available yet, open Steam's **Steam Guard** page first, then return to the export dialog.

<a id="mafile"></a>

## 📦 maFile import, export & restoration

Open **Steam → Menu → Millennium → General → maFile import and export**.

### Export and compatibility

- Select an account when more than one is available, then tap **Export file** to save a `.maFile`.
- View the username, SteamID, authenticator secrets, device ID, revocation code, and login tokens when available. **Tap a field to copy its complete value.** Long values use a single-line ellipsis; copying and exporting preserve the full content.
- Export matches authenticator data and login credentials by SteamID. Additional maFile fields are retained when importing and re-exporting.
- Supports the single-account plaintext maFile format used by **Monica Steam** and **Steam Desktop Authenticator (SDA)**. Import accepts files up to **1 MB**; ZIP archives and encrypted backups are not supported.
- **Monica Steam interoperability has been tested:** exported maFiles support Steam Guard codes and login approvals on multiple devices. Import the saved file through Monica's own maFile import screen.

**Get from Monica Steam:** on the Import tab, this shortcut requests one account from a compatible Monica Steam installation in the same Android user/profile. Monica requires a configured master password, a fresh unlock, account selection and explicit confirmation. The returned maFile is opened once through a short-lived UID-bound grant, without saving a plaintext temporary file, then shown in the existing preview. Press **Import** to apply it; receiving it does not import or restore automatically. Steam and injected code can retain the credentials after reading them.

This needs a Monica Steam build implementing [maFile handoff protocol v1](docs/monica-mafile-handoff.md), currently being added to its 1.0.310 development tree. Older/missing installations fall back to the existing **Choose maFile** button. This new shortcut needs device interoperability testing; the file-transfer verification above does not establish that the shortcut has been tested with Steam/LSPosed.

### Restore in the official Steam app

1. On the source phone, open Steam's **Steam Guard** page, then export the account's maFile through Millennium.
2. On the destination phone with Millennium active, open the **Import** tab, choose the file, review the account, and tap **Import**.
3. Import replaces saved data for the same **SteamID** and automatically queues restoration when the host and credentials are supported. A matching account appears only once in the list.
4. **Force-stop Steam in Android Settings, then reopen it.** Returning from the background does not apply restoration.
5. Check the restore status and Steam's **Steam Guard** page. A valid session for that account is required for online login approvals.

If authenticator data is available but login credentials are missing, expired, or invalid, Millennium restores the authenticator and keeps the current login session. A file containing only usable login credentials can restore the session, but cannot supply missing authenticator secrets. Steam validates login credentials online.

Other account records are preserved. A device ID included in the file also updates Steam's global device ID. You can queue multiple accounts before restarting, cancel a pending restore, or use **Restore account data** to schedule it again.

Imported accounts and restoration backups are encrypted in private app storage. Failed writes trigger rollback; interrupted restores are not automatically replayed. **Undo** restores the saved previous state only when the current data still matches the restore record. After a successful restore, exports use Steam's current session credentials.

<a id="steam-management"></a>

## 🧰 Steam management

Select **Settings → Working mode → Shizuku(Sui)** with the service running, then grant access. The mode is enabled only after authorization succeeds.

Under **General → Steam Management**, **Uninstall Steam and keep data** removes Steam while retaining its app data for the current Android user. Steam closes when the operation starts. Reinstalling with retained data requires a matching signing certificate; official and NPatch-patched builds may use different signatures. Keeping app data is not a substitute for a maFile backup.

<a id="quick-start"></a>

## 🚀 Quick Start

### Requirements

| Item | Requirement |
| :--- | :--- |
| Android | Android 8.0 / API 26 or later, subject to the Xposed framework's own requirements. |
| Architecture | `arm64-v8a`. |
| Framework for the standalone module | LSPosed or a compatible framework whose **core supports modern libxposed API 102**. |
| Target app | Steam for Android: `com.valvesoftware.android.steam.community`. |
| Optional effect | Android 13 or later for Liquid Glass; earlier versions use the regular module navigation bar. |
| Optional management service | Shizuku or Sui, running and authorized, for Steam management operations. |

### Downloads

[GitHub Releases](https://github.com/Good-Joe2049/Millennium/releases/latest) provides the latest files and release notes. The v1.1.5 release includes:

| File | Purpose |
| :--- | :--- |
| `Millennium-v1.1.5-arm64-v8a.apk` | Standalone module for the framework installation below. |
| `steam-1.1.5-npatched.apk` | NPatch-patched Steam APK, distributed separately from the standalone module. |

### Framework installation

1. Download and install the APK from [GitHub Releases](https://github.com/Good-Joe2049/Millennium/releases).
2. Enable **Millennium** in your Xposed framework manager.
3. Select **Steam** in the module scope.
4. **Force-stop Steam and reopen it** to load the module.
5. Open Steam's rightmost **Menu** tab, then select **Millennium** above **Store**.
6. Visit a game's store page and tap the floating button to view its data. Steam Guard export and maFile import/export are available on the module's General page.

The floating panel belongs to Steam's own window and does not require Android's “Display over other apps” permission.

<a id="data"></a>

## 📊 Data sources

- **Current players:** Steam's official API, with SteamDB as a fallback.
- **Peaks, followers, and last update:** SteamDB game information.
- **Historical lows:** SteamDB pricing data for the current store currency and region.
- **SteamDB rating:** calculated locally from the store page's positive and negative review counts, using the SteamDB formula.

Availability depends on the upstream services and the current Steam page. Missing fields are shown as unavailable instead of being treated as zero.

<a id="faq"></a>

## 💬 FAQ

<details>
<summary><strong>Why is the menu entry or floating button missing?</strong></summary>

Check that the module is enabled, Steam is selected in its scope, and the framework core supports API 102. Force-stop Steam and open it again. If the Millennium entry is present, check the SteamDB floating panel switch.

Menu detection currently recognizes the Chinese “商店” and English “Store” labels. Changing the module language does not add support for other Steam menu languages. Steam updates may also require compatibility changes.

</details>

<details>
<summary><strong>Why are some statistics unavailable or different from what Steam shows?</strong></summary>

Steam and SteamDB are separate sources and may have different availability or update times. Prices follow the store's currency and pricing region, not the phone's language. The SteamDB rating accounts for review count and is not the same as Steam's positive-review percentage.

</details>

<details>
<summary><strong>Why does direct import fail to open an authenticator?</strong></summary>

Install and enable the selected app in the same Android user or profile as Steam. Check any system restrictions on launching other apps. If the authenticator is locked, complete its password or biometric prompt. You can also use the QR code or copy the individual fields.

</details>

<details>
<summary><strong>Why did importing a maFile not immediately restore Steam Guard?</strong></summary>

Restoration runs at the next full Steam startup. Check that the dialog shows a queued restore, then force-stop Steam in Android Settings and reopen it. Importing another file for the same SteamID replaces that account's saved data and pending request.

If restoration is unavailable, the file can still be saved and re-exported. A login-only file cannot restore missing authenticator secrets; login approvals also need a valid session for the account. Include the restore status and Steam version when reporting a problem.

</details>

<details>
<summary><strong>Can Millennium open Monica and import the account directly?</strong></summary>

For **Monica Steam → Millennium**, use **Get from Monica Steam** with a compatible build and approve the request in Monica. For **Millennium → Monica Steam**, export the maFile and select it in Monica Steam's import screen; automatic handoff in that direction is not implemented. Direct app-to-app import is also available for Stratum and Aegis through the Steam Guard export dialog.

</details>

<a id="build"></a>

## 🛠️ Build from source

```bash
git clone https://github.com/Good-Joe2049/Millennium.git
cd Millennium
```

Open the project in Android Studio and sync Gradle. The current configuration uses **Android SDK 37**, a **JDK 25 Gradle daemon toolchain**, and **Java 21 source/target compatibility**. Use the repository's Gradle Wrapper; dependency versions are defined in `gradle/libs.versions.toml`.

<details>
<summary><strong>Build commands and APK output</strong></summary>

Windows / PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Linux / macOS:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Outputs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`. Release filenames follow `Millennium-v<versionName>-<ABI>.apk`, for example `Millennium-v1.1.5-arm64-v8a.apk`.

Release builds enable R8 and resource shrinking. Keep the provided ProGuard rules. An unsigned release APK must be signed before installation; Android Studio's **Generate Signed App Bundle or APK → APK** can do this.

</details>

<a id="contributing"></a>

## 🤝 Feedback & contributions

[Issues](https://github.com/Good-Joe2049/Millennium/issues) and [pull requests](https://github.com/Good-Joe2049/Millennium/pulls) are welcome. Include the device and Android version, framework and module versions, Steam version, reproduction steps, and relevant logs. For store-data issues, include the game's store link or AppID.

Follow the [Telegram channel](https://t.me/MillenniumXposed) for release announcements and project updates.

Exported maFiles contain plaintext credentials, and session diagnostic logs can include login tokens. Before sharing screenshots or logs, redact account details, authenticator secrets, access/refresh tokens, and QR codes. Do not attach an unredacted maFile to an issue.

## 🙏 Acknowledgements

- [SteamDB Browser Extension](https://github.com/SteamDatabase/BrowserExtension) — data fields, regional pricing, and rating calculations.
- [libxposed](https://github.com/libxposed/api) — the modern Xposed module API.
- [miuix](https://github.com/compose-miuix-ui/miuix) — module settings components and interactions.
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — Liquid Glass effects.
- [Stratum](https://github.com/stratumauth/app) and [Aegis](https://github.com/beemdevelopment/aegis) — open-source authenticator apps.
- [Steam Desktop Authenticator](https://github.com/Jessecar96/SteamDesktopAuthenticator) — references for Steam maFile, Steam Guard, and trade confirmations.
- [steamguard-cli](https://github.com/dyc3/steamguard-cli) — references for Steam Guard sign-in, authenticator transfers, and confirmation protocols.
- [AnotherVaporAuth](https://github.com/freefrank/AnotherVaporAuth) — references for the Steam mobile authenticator and login approvals.

## 📄 License

Millennium is licensed under the **[MIT License](LICENSE)**. Third-party code, dependencies, and assets retain their respective licenses.

This is an independent community project and is not affiliated with Valve, Steam, or SteamDB.

---

<p align="center">
  <a href="https://github.com/Good-Joe2049/Millennium/releases">Releases</a> •
  <a href="https://github.com/Good-Joe2049/Millennium/issues">Issues</a> •
  <a href="https://t.me/MillenniumXposed">Telegram</a> •
  <a href="#top">Back to top ↑</a>
</p>

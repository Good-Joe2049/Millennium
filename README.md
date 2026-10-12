<a id="top"></a>

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" height="128" alt="Millennium logo">
</p>

<h1 align="center">Millennium</h1>

<p align="center">
  <strong>SteamDB insights and Steam Guard export, right inside Steam.</strong><br>
  An Android Steam enhancement module powered by modern libxposed.
</p>

<p align="center">
  <a href="README_zh.md">简体中文</a> •
  <a href="#features">Features</a> •
  <a href="#steam-guard">Steam Guard</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="https://github.com/Good-Joe2049/Millennium/issues">Feedback</a>
</p>

<p align="center">
  <a href="https://github.com/Good-Joe2049/Millennium/releases">
    <img src="https://img.shields.io/github/v/release/Good-Joe2049/Millennium?style=for-the-badge&amp;label=Download%20APK&amp;logo=github" alt="Download the latest APK on GitHub">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8.0 or later">
  <img src="https://img.shields.io/badge/libxposed-API%20102-66C0F4?style=flat-square" alt="libxposed API 102">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-1B2838?style=flat-square" alt="arm64-v8a">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-66C0F4?style=flat-square" alt="MIT License"></a>
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

**Millennium** brings game statistics, price history, and authenticator export to the Android Steam app. Open a floating SteamDB panel while browsing a game's store page, or use the **Millennium** entry above **Store** in Steam's menu to manage the module.

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

<a id="quick-start"></a>

## 🚀 Quick Start

### Requirements

| Item | Requirement |
| :--- | :--- |
| Android | Android 8.0 / API 26 or later, subject to the Xposed framework's own requirements. |
| Architecture | `arm64-v8a`. |
| Framework | LSPosed or a compatible framework whose **core supports modern libxposed API 102**. |
| Target app | Steam for Android: `com.valvesoftware.android.steam.community`. |
| Optional effect | Android 13 or later for Liquid Glass; earlier versions use the regular module navigation bar. |

### Installation

1. Download and install the APK from [GitHub Releases](https://github.com/Good-Joe2049/Millennium/releases).
2. Enable **Millennium** in your Xposed framework manager.
3. Select **Steam** in the module scope.
4. **Force-stop Steam and reopen it** to load the module.
5. Open Steam's rightmost **Menu** tab, then select **Millennium** above **Store**.
6. Visit a game's store page and tap the floating button to view its data. Steam Guard export is available on the module's General page.

The floating panel belongs to Steam's own window and does not require Android's “Display over other apps” permission. A rootless installation method has not been verified.

<a id="data"></a>

## 📊 Data sources

- **Current players:** Steam's official API, with SteamDB as a fallback.
- **Peaks, followers, and last update:** SteamDB game information.
- **Historical lows:** SteamDB pricing data for the current store currency and region.
- **SteamDB rating:** calculated locally from the store page's positive and negative review counts, using the SteamDB formula.

Queries share a request and cache layer built on Android's `HttpURLConnection`. Cronet and its bundled dependencies have been removed to reduce APK size. Data is loaded when the panel is opened or refreshed; successful results are cached briefly, rather than polled continuously in the background.

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

Outputs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`. Release filenames follow `Millennium-v<versionName>-<ABI>.apk`, for example `Millennium-v1.1.0-arm64-v8a.apk`.

Release builds enable R8 and resource shrinking. Keep the provided ProGuard rules. An unsigned release APK must be signed before installation; Android Studio's **Generate Signed App Bundle or APK → APK** can do this.

</details>

<a id="contributing"></a>

## 🤝 Feedback & contributions

[Issues](https://github.com/Good-Joe2049/Millennium/issues) and [pull requests](https://github.com/Good-Joe2049/Millennium/pulls) are welcome. Include the device and Android version, framework and module versions, Steam version, reproduction steps, and relevant logs. For store-data issues, include the game's store link or AppID.

Redact account details, authenticator secrets, and QR codes before sharing screenshots or logs.

## 🙏 Acknowledgements

- [SteamDB Browser Extension](https://github.com/SteamDatabase/BrowserExtension) — data fields, regional pricing, and rating calculations.
- [libxposed](https://github.com/libxposed/api) — the modern Xposed module API.
- [miuix](https://github.com/compose-miuix-ui/miuix) — module settings components and interactions.
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — Liquid Glass effects.
- [ZXing](https://github.com/zxing/zxing) — local QR code generation.
- [Stratum](https://github.com/stratumauth/app) and [Aegis](https://github.com/beemdevelopment/aegis) — open-source authenticator apps.
- [Aether](https://github.com/Zhou-Shilin/Aether) — README layout inspiration.

## 📄 License

Millennium is licensed under the **[MIT License](LICENSE)**. Third-party code, dependencies, and assets retain their respective licenses.

This is an independent community project and is not affiliated with Valve, Steam, or SteamDB.

---

<p align="center">
  <a href="https://github.com/Good-Joe2049/Millennium/releases">Releases</a> •
  <a href="https://github.com/Good-Joe2049/Millennium/issues">Issues</a> •
  <a href="#top">Back to top ↑</a>
</p>

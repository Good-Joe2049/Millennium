<a id="top"></a>

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" height="128" alt="Millennium 图标">
</p>

<h1 align="center">Millennium</h1>

<p align="center">
  <strong>在 Steam 内查看 SteamDB 数据，导出 Steam 令牌。</strong><br>
  基于现代 libxposed 的 Android Steam 增强模块。
</p>

<p align="center">
  <a href="README.md">English</a> •
  <a href="#features">核心功能</a> •
  <a href="#steam-guard">令牌导出</a> •
  <a href="#quick-start">快速开始</a> •
  <a href="https://github.com/Good-Joe2049/Millennium/issues">问题反馈</a>
</p>

<p align="center">
  <a href="https://github.com/Good-Joe2049/Millennium/releases/latest">
    <img src="https://img.shields.io/github/v/release/Good-Joe2049/Millennium?style=for-the-badge&amp;label=%E4%B8%8B%E8%BD%BD%20APK&amp;logo=github" alt="从 GitHub 下载最新 APK">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8.0 或更高版本">
  <img src="https://img.shields.io/badge/libxposed-API%20102-66C0F4?style=flat-square" alt="libxposed API 102">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-1B2838?style=flat-square" alt="arm64-v8a">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-66C0F4?style=flat-square" alt="MIT 许可证"></a>
</p>

<table align="center">
  <tr>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/panel-witcher3.jpg"><img src="docs/images/panel-witcher3.jpg" width="280" alt="巫师 3 商店页面中的 SteamDB 悬浮面板"></a><br>
      <sub>SteamDB 悬浮面板</sub>
    </td>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/steam-menu-preview.jpg"><img src="docs/images/steam-menu-preview.jpg" width="280" alt="Steam 菜单中的 Millennium 入口"></a><br>
      <sub>Steam 菜单入口</sub>
    </td>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/module-settings-preview.jpg"><img src="docs/images/module-settings-preview.jpg" width="280" alt="带有液态玻璃底栏的 Millennium 常规页面"></a><br>
      <sub>模块设置主页</sub>
    </td>
    <td width="25%" align="center" valign="top">
      <a href="docs/images/steam-guard-export-preview.jpg"><img src="docs/images/steam-guard-export-preview.jpg" width="280" alt="已遮挡敏感数据的 Steam 令牌导出弹窗"></a><br>
      <sub>Steam 令牌导出</sub>
    </td>
  </tr>
</table>

---

**Millennium** 将游戏数据、价格记录和令牌导出整合进 Android 版 Steam。在游戏商店详情页打开 SteamDB 悬浮面板，或从 Steam 菜单中位于“商店”上方的 **Millennium** 入口管理模块。

<a id="features"></a>

## ✨ 核心功能

| 功能 | 可以查看或完成什么 |
| :--- | :--- |
| **SteamDB 悬浮面板** | 实时在线人数、24 小时峰值、历史峰值、关注数、历史最低价、SteamDB 评分和最近更新日期。 |
| **价格记录** | 跟随商店币种与定价地区，显示可用的折扣、出现次数与日期；SteamDB 提供时，同时展示两年内最低价。 |
| **悬浮操作** | 拖动悬浮球、贴边并记住位置，手动刷新当前游戏数据，或在浏览器打开对应的 SteamDB 页面。 |
| **模块设置** | 全屏 miuix 界面，包含常规、主题、日志、设置四个底栏页面，支持左右滑动与页面过渡动画。 |
| **液态玻璃底栏** | 可选的玻璃效果，支持拖动选中胶囊、弹性反馈与光学效果，需要 Android 13 或更高版本。 |
| **Steam 令牌导出** | 查看类型、用户名、服务商和密钥，轻触数据即可复制；存在多个账号时可切换账号。 |
| **二维码与直接导入** | 在 Stratum 和 Aegis 两种格式之间切换，二维码带有形变过渡，也可直接打开选中的身份验证器导入。 |
| **模块语言** | 跟随系统、English、中文（简体），切换即时生效并保存选择。 |

<a id="interface"></a>

## 📱 界面与交互

模块设置采用 [miuix](https://github.com/compose-miuix-ui/miuix) 的卡片、开关、弹窗与选择菜单。既可以点击底栏，也可以左右滑动页面切换。

在 **常规 → 界面美化** 中开启 **液态玻璃底栏**，即可为模块底栏应用玻璃效果。选中胶囊可以在各个页面之间拖动；该效果仅用于模块设置页面，不改变 Steam 自带的底栏。

**SteamDB 悬浮面板**开关即时生效。**设置 → 语言**提供模块语言选择，不会修改 Steam 自身的语言。

**主题**和**日志**目前为占位页面，其中主题页面用于未来的 **Steam 主题**功能。配置备份与恢复，以及模块界面可选的深色、浅色、莫奈主题，也仍在规划中。

<a id="steam-guard"></a>

## 🔐 Steam 令牌导出

打开 **Steam → 菜单 → Millennium → 常规 → Steam 令牌导出**。

- **轻触复制**：每个数据值都有独立的按压反馈，复制后显示剪贴板提示。
- **扫码导入**：选择 Stratum 或 Aegis，显示对应格式的二维码，一次只展示一个。
- **直接添加**：点击 **添加到 Stratum** 或 **添加到 Aegis**，使用正确格式的链接打开对应软件，无需手动输入密钥或扫码。

| 身份验证器 | 直接导入时的预期流程 |
| :--- | :--- |
| [Stratum](https://github.com/stratumauth/app) | 打开应用后添加 Steam 令牌；如有应用锁，需要先解锁。 |
| [Aegis](https://github.com/beemdevelopment/aegis) | 打开已经填好数据的页面，确认后保存；如有解锁提示，先完成验证。 |

两款软件使用不同的 Steam 链接格式，Millennium 会为二维码和直接导入选择各自对应的格式。请先安装并完成身份验证器的初始化；具体确认流程可能随其版本变化。

二维码在设备本地生成。模块已取消“启动 Steam 后自动复制令牌数据到剪贴板”的旧行为。如果暂时没有获取到数据，请先打开 Steam 的**令牌**页面，再返回导出弹窗。

<a id="quick-start"></a>

## 🚀 快速开始

### 环境要求

| 项目 | 要求 |
| :--- | :--- |
| Android | Android 8.0 / API 26 或更高版本，同时满足所用 Xposed 框架的要求。 |
| 架构 | `arm64-v8a`。 |
| 框架 | **核心支持现代 libxposed API 102** 的 LSPosed 或兼容框架。 |
| 目标应用 | Android 版 Steam：`com.valvesoftware.android.steam.community`。 |
| 可选效果 | 液态玻璃需要 Android 13 或更高版本；较早的系统使用普通模块底栏。 |

### 安装步骤

1. 从 [GitHub Releases](https://github.com/Good-Joe2049/Millennium/releases) 下载并安装模块 APK。
2. 在 Xposed 框架管理器中启用 **Millennium**。
3. 在模块作用域中勾选 **Steam**。
4. **强制停止 Steam，再重新打开**，让框架加载模块。
5. 打开 Steam 底栏最右侧的**菜单**，点击“商店”上方的 **Millennium**。
6. 进入某款游戏的商店详情页，点击悬浮球查看数据；令牌导出位于模块的常规页面。

悬浮面板附着在 Steam 自身的窗口中，无需申请 Android 的“显示在其他应用上层”权限。目前尚未验证免 Root 安装方式。

<a id="data"></a>

## 📊 数据来源

- **实时在线人数**：优先使用 Steam 官方接口，SteamDB 作为后备。
- **峰值、关注数与最近更新**：来自 SteamDB 游戏信息。
- **历史最低价**：来自当前商店币种与定价地区对应的 SteamDB 价格记录。
- **SteamDB 评分**：读取商店页面的好评与差评数量，在本地按 SteamDB 公式计算。

各项查询共用基于 Android `HttpURLConnection` 的请求与缓存层。已移除 Cronet 及其随包依赖，以减少 APK 体积。打开或刷新面板时读取数据，并短期缓存成功结果，不会在后台持续轮询。

数据可用性取决于上游服务和当前 Steam 页面；缺失字段会显示为不可用，不会当作零值。

<a id="faq"></a>

## 💬 常见问题

<details>
<summary><strong>为什么看不到菜单入口或悬浮球？</strong></summary>

确认模块已启用、作用域选择了 Steam，且框架核心支持 API 102，然后强制停止并重新打开 Steam。已有 Millennium 入口时，请检查 SteamDB 悬浮面板开关。

当前菜单识别支持中文“商店”和英文“Store”。切换模块语言不会扩展 Steam 菜单语言的兼容范围；Steam 更新也可能需要相应适配。

</details>

<details>
<summary><strong>为什么部分数据不可用，或者与 Steam 显示的不一样？</strong></summary>

Steam 和 SteamDB 是不同的数据来源，可用性与更新时间可能不同。价格跟随商店的币种和定价地区，而非手机语言。SteamDB 评分会考虑评价样本量，因此不等于 Steam 的好评百分比。

</details>

<details>
<summary><strong>为什么直接导入时无法打开身份验证器？</strong></summary>

请在与 Steam 相同的 Android 用户或工作资料中安装并启用对应软件，并检查系统是否限制应用间启动。如果身份验证器已锁定，请完成密码或指纹验证。也可以使用二维码或逐项复制数据。

</details>

<a id="build"></a>

## 🛠️ 从源码构建

```bash
git clone https://github.com/Good-Joe2049/Millennium.git
cd Millennium
```

在 Android Studio 中打开项目并同步 Gradle。当前配置使用 **Android SDK 37**、**JDK 25 Gradle Daemon 工具链**，Java 源码与目标编译级别为 **21**。请使用仓库自带的 Gradle Wrapper，依赖版本见 `gradle/libs.versions.toml`。

<details>
<summary><strong>构建命令与 APK 输出</strong></summary>

Windows / PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Linux / macOS：

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

产物分别位于 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`。Release 文件名遵循 `Millennium-v<versionName>-<ABI>.apk`，例如 `Millennium-v1.1.0-arm64-v8a.apk`。

Release 构建启用 R8 与资源缩减，请保留项目中的 ProGuard 规则。未签名的 Release APK 需要签名后才能安装，也可以通过 Android Studio 的 **Generate Signed App Bundle or APK → APK** 生成签名 APK。

</details>

<a id="contributing"></a>

## 🤝 反馈与贡献

欢迎通过 [Issues](https://github.com/Good-Joe2049/Millennium/issues) 和 [Pull Requests](https://github.com/Good-Joe2049/Millennium/pulls) 报告问题、提出建议或贡献改进。请附上设备与 Android 版本、框架与模块版本、Steam 版本、复现步骤及相关日志。商店数据问题请同时提供游戏链接或 AppID。

分享截图或日志前，请遮挡账号信息、令牌密钥和二维码。

## 🙏 致谢

- [SteamDB Browser Extension](https://github.com/SteamDatabase/BrowserExtension) — 数据字段、区域价格与评分算法参考。
- [libxposed](https://github.com/libxposed/api) — 现代 Xposed 模块 API。
- [miuix](https://github.com/compose-miuix-ui/miuix) — 模块设置的界面组件与交互。
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — 液态玻璃效果。
- [ZXing](https://github.com/zxing/zxing) — 本地二维码生成。
- [Stratum](https://github.com/stratumauth/app) 与 [Aegis](https://github.com/beemdevelopment/aegis) — 开源身份验证器。
- [Aether](https://github.com/Zhou-Shilin/Aether) — README 排版参考。

## 📄 开源协议

Millennium 采用 **[MIT License](LICENSE)**。第三方代码、依赖和资源仍分别遵循其原始许可证。

本项目由社区独立开发，与 Valve、Steam 或 SteamDB 无官方隶属关系。

---

<p align="center">
  <a href="https://github.com/Good-Joe2049/Millennium/releases">版本下载</a> •
  <a href="https://github.com/Good-Joe2049/Millennium/issues">问题反馈</a> •
  <a href="#top">返回顶部 ↑</a>
</p>

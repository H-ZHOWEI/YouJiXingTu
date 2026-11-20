# 油迹星图（Android Studio 原生版）

由原 HBuilderX 5+ App（`index.html + style.css + script.js`）用 **Kotlin + Android 原生控件**重写而来。不是 WebView 套壳，界面、交互、存储与计算全部为原生实现。

## 功能对照

| 旧版功能 | 新版实现 |
| --- | --- |
| 记录 / 统计 / 加油站 / 历史 四页签 | 底部导航四个 Fragment |
| 日期时间、公里数、"我忘记了" | 原生日期时间选择器 + 复选框禁用输入 |
| 加油信息、油品、油箱状态 | 原生表单 + Spinner + RadioGroup |
| 里程校验（同日顺序、跨日最大值） | `Analytics.validateMileage` |
| 平均油耗 / 剩余油量 / 可行驶距离 / 油箱容量估算 | `Analytics` 按旧版公式移植 |
| 油表格数估算（v1.14） | 按加油前后格数推算每格升数/公里/钱，样本越多越准；总格数可配，无格数时回退旧算法 |
| 拍照、相册、文本提取 | v1.14 已移除（识别不准，改纯手动录入） |
| 勾选加油选加油站 | 高德定位 + 5 公里内 POI 周边搜索，点选自动填入（需自行配 Key） |
| 本周/本月/本年/全部统计、数据质量、空档提示 | `StatsFragment` |
| 加油站对比与评分 | `StationsFragment` |
| 导入导出、备份、冗余清理、分页 | `HistoryFragment` |
| 星空主题、应用图标 | `StarFieldView` + 原版图标 |
| 版本更新 | 启动/手动检查 GitHub Release，多镜像下载安装（横幅只显示当前版本说明，v1.13） |
| 智能化提示（v1.12，15 项） | 加满/油品自动推断、常用地点补全、重复与异常录入确认、低油量主动提醒、下次加油预测、异常油耗与数据质量建议（OCR 相关项随拍照功能在 v1.14 移除） |
| 车载蓝牙自动打开（v1.15，v1.16 修复授权） | 连接指定蓝牙（如车机）后自动启动本软件；`BluetoothAutoStartReceiver` 监听 ACL_CONNECTED，需蓝牙名匹配 + 蓝牙/悬浮窗权限。v1.16 补上 Manifest 的 `SYSTEM_ALERT_WINDOW` 声明，修复系统设置里找不到本应用、授权后仍不触发的问题 |
| 输入法不遮挡输入框（v1.15） | `InsetsUtil` 底部 padding 取 systemBars 与 IME insets 较大值，键盘弹出时表单自动上移 |

## 目录结构

```
app/src/main/java/com/youjixingtu/app/
  YouJiApp.kt              应用入口，初始化仓库、高德隐私合规与 Key 注入（v1.10）
  model/Records.kt         与旧版 JSON 兼容的数据模型
  data/Repository.kt       SharedPreferences 存储/备份/照片
  core/Analytics.kt        全部统计与校验算法（v1.11 剩余油量按加满正序推算；v1.14 新增油表格数法 gaugeEstimate，越用越准）
  ui/                      MainActivity 与四个页签、编辑页、OCR 页、附近加油站选择页
  util/UpdateManager.kt    版本更新
  util/BluetoothAutoStartReceiver.kt 车载蓝牙连接后自动启动（v1.15）
  util/TextExtractHelper.kt 小票文本提取
  util/CryptoUtil.kt       Keystore AES-GCM 透明加密（v1.7）
  (高德 SDK 接入在 ui/NearbyStationActivity.kt，Key 可在 App 内设置或写 Manifest 占位符)
app/src/main/res/          布局、主题、图标、FileProvider 配置
```

## 操作说明（先看这个）

面向普通用户、按软件实际界面写的完整使用说明见 **[操作说明.md](操作说明.md)**，涵盖：安装、四个页签、记录一条星迹、油量估算、拍照 OCR、统计、加油站对比、附近加油站选站与高德 Key 配置、历史管理、导入导出、版本更新、常见问题。

- 新手直接看《操作说明.md》即可，从零上手。
- 开发者想了解实现与发版流程，继续看下面几节。

## 打开与运行

1. 用 Android Studio 打开本目录（`YouJiXingTu/`）。
2. 首次打开等待 Gradle Sync 完成（需要 Android SDK Platform 37、Build-Tools 34+）。
3. 连接手机或启动模拟器，点击 Run。
4. 打包：`Build > Generate Signed Bundle / APK`，生成 APK 后按下方发版流程发布。

## 数据迁移

旧版 App 中打开「历史 → 导出星图」，复制 JSON；新版「历史 → 导入星图」粘贴即可。重复 `id` 的记录会自动跳过。

## 发版与更新

- 更新检查地址写在 `util/UpdateManager.kt` 顶部：`H-ZHOWEI/YouJiXingTu`。如需换仓库，改这一处即可。
- 每次改功能都要升 `app/build.gradle.kts` 里的 `versionCode` 和 `versionName`。
- 发 GitHub Release：tag 用 `vX.Y`，资产名用 ASCII，例如 `YouJiXingTu-v1.0-release.apk`。
- Release 正文取 `CHANGELOG.md` 对应版本段落。
- 更新逻辑沿用既定规则：每次打开检查、失败不写冷却、非模态横幅、跳过只对该版本且只对启动检查生效、下载多镜像重试并按大小校验、安装走 FileProvider。

## 附近加油站（高德 Key）

勾选「本次加油了」会弹出附近加油站选择页，用高德 SDK 定位并搜索 5 公里内的加油站。按合规要求软件不内置任何地图 Key，需自行到 <https://console.amap.com> 免费申请 Android 平台 Key。**v1.7 起推荐在 App 内配置**：进入「选择加油站」页 → 点「设置Key」→ 粘贴 Key → 保存即生效，免改代码、免重装；App 内配置的 Key 优先于 Manifest。也可在编译前把 Key 填入 `app/src/main/AndroidManifest.xml` 的 `com.amap.api.v2.apikey` `android:value`（留空即可，不要填中文占位文字）。v1.10 补齐了高德定位/搜索 SDK 强制要求的隐私合规声明，修复「填了 Key 仍提示定位不可用」的问题。申请时包名填 `com.youjixingtu.app`，调试签名 SHA1 为 `98:52:6E:3A:80:E5:2A:60:E8:59:49:CC:0C:94:E5:9F:98:4C:D0:83`。详细步骤见《操作说明》第八节。不配 Key 不影响其它功能，只是附近加油站列表为空、需手动输入站名。

## 数据安全（v1.7）

加油记录与照片列表在本地用 Android Keystore 中的 AES-256 密钥透明加密存储，密钥由系统硬件保管、不随应用备份导出；`allowBackup` 已关闭。旧版本未加密的数据会在首次读取时自动兼容，保存时平滑升级为加密格式。若系统 Keystore 不可用会自动降级为明文，绝不因加密失败丢数据。

## 说明

小票识别用 Google ML Kit 中文离线 OCR 在手机本地完成，不联网、免费；识别出的文字可在页面上修改后再提取字段，提取规则与旧版一致。

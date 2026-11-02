# 隐私守卫（ANDRIOD-GUARD）

一个自用的安卓隐私守护 App（侧载安装，不上架 Google Play），目标是**防止照片等隐私被不认识的应用读取**。

普通 App 拦不住别的 App 读照片——真正把关的是系统权限。所以本应用分两层：

1. **审计模式**（不需要任何额外条件）：列出谁拿到了敏感权限，给出带理由的风险评级，一键跳到系统设置去撤销。
2. **Shizuku 模式**（借助 [Shizuku](https://shizuku.rikka.app/) 获得 adb shell 级别的权限，不需要 root）：查看每个应用实际的访问记录（appops），在 App 内直接撤销权限，按策略自动收紧，并保留完整的操作日志。

本应用**没有网络权限**：合并后的清单里显式移除了 `android.permission.INTERNET`，CI 每次都会用 `aapt2 dump permissions` 检查构建出的 APK。

---

## 功能

### 审计模式（不需要 Shizuku）

- 用 `PackageManager` 枚举所有已安装应用（声明了 `QUERY_ALL_PACKAGES`），通过 `requestedPermissionsFlags` 读取**实际已授予**的权限：
  - 照片/媒体：`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`（完全授权）、`READ_MEDIA_VISUAL_USER_SELECTED`（Android 14+ 的部分授权，单独标出）、旧版 `READ_EXTERNAL_STORAGE`；
  - `ACCESS_MEDIA_LOCATION`：能读照片里的拍摄位置；
  - 所有文件访问 `MANAGE_EXTERNAL_STORAGE`（这是一个 appop，见下文“行为核实”）；
  - 相机、麦克风；定位，区分前台定位和后台定位 `ACCESS_BACKGROUND_LOCATION`。
- 风险标记：最近新装（`firstInstallTime`）、不是从应用商店装的（API 30+ 用 `getInstallSourceInfo`，API 33+ 还参考 `packageSource`）、后台定位、所有文件访问、照片完全授权而非部分授权。
- 每个应用一个风险等级（无 / 低 / 中 / 高 / 严重），**详情页列出每一条评级理由和分数**。
- 每个应用都有“打开系统设置撤销权限”按钮（`ACTION_APPLICATION_DETAILS_SETTINGS`），申请了所有文件访问的应用还有直达“所有文件访问”设置页的按钮。
- 可以把应用标为“信任”：照常评级，但不再告警，也不会被自动收紧。
- 系统应用默认折叠。
- 后台定期检查，发现新的“中风险”及以上应用时发通知（可关闭）。

### Shizuku 模式

- 处理好四种状态：**没装 Shizuku、Shizuku 没启动、没授权、已授权**（另外还处理了 Shizuku 版本过旧）。没装或没启动时自动退回审计模式，界面上有安装 / 启动 / 授权的引导。
- **访问记录**：每个应用最近一次实际使用 照片/媒体、相机、麦克风、定位、剪贴板（`READ_CLIPBOARD`）的时间，以及最近一次被拒绝的时间和“正在使用”状态。剪贴板记录只有这个模式能看到。
- **直接撤销**：在 App 里 `pm revoke` 撤销运行时权限、`pm grant` 恢复；把 `READ_CLIPBOARD` 设为 `ignore`（屏蔽剪贴板读取）；把 `MANAGE_EXTERNAL_STORAGE` 设为 `deny`（关闭所有文件访问）。每个操作执行前都会弹窗说明实际效果，执行后自动复查结果。
- **实验**：把运行时权限对应的 appop 设为 `ignore`，3 秒后复查它有没有被系统改回——用来在你自己的设备上验证下文的 AOSP 结论。
- **自动收紧**：可配置的策略（例如“不在信任列表里的应用，一旦拿到照片权限，就自动撤销”）。用 WorkManager 定期对比授权快照，并在每次打开 App 时检查一次。每次自动操作都发通知，通知上可**一键撤回**。
- **操作日志**：记录 App 自己做过的每一次撤销 / 收紧 / 恢复 / 实验，以及触发方式（手动、后台定期检查、打开 App 时检查、手动“立即检查”、通知撤回、日志撤回、实验），变更前的模式和复查结果。日志里的操作可以撤回。

### 两种模式能做到和做不到的

| | 审计模式 | Shizuku 模式 |
|---|---|---|
| 看到谁拿到了哪些敏感权限 | ✅ | ✅ |
| 所有文件访问是否开启 | ✅（Android 11+，见下文；若 ROM 不允许查询则提示“需要 Shizuku 确认”） | ✅ |
| 风险评级与理由 | ✅ | ✅（另外加上“最近读过剪贴板”） |
| 跳转系统设置撤销 | ✅ | ✅ |
| 实际访问记录（最近一次使用时间） | ❌ 普通应用读不到别人的 appops | ✅ |
| 剪贴板读取记录 / 屏蔽剪贴板 | ❌ | ✅ |
| 在 App 内直接撤销 | ❌ 只能跳转设置 | ✅ |
| 自动收紧 | ❌ 只能发通知提醒 | ✅ |

两种模式都**做不到**的：

- 拦截已经拿到权限的应用“这一次”的读取：撤销之后它才拿不到。已经读走的数据收不回来。
- appops 只记录**每类访问最近一次**的时间，不是完整的访问历史。
- 不能阻止对方以后再弹窗申请权限（你拒绝即可）。
- 自动收紧依赖 Shizuku 运行。手机重启后 Shizuku 需要重新启动；不可用期间检测到的新授权只会通知你，等 Shizuku 可用后（打开本应用或下一次后台检查）再处理。
- 后台检查的时机由系统决定，可能因省电被推迟，最短间隔 15 分钟。

---

## 行为核实（依据 AOSP 源码）

以下结论都是对照 AOSP `android10-release` ～ `android15-release` 分支的源码得出的（来源：GitHub 上的 `aosp-mirror/platform_frameworks_base` 镜像）。**这些只在源码层面核实过，没有在真机上逐一验证**；厂商 ROM 可能有改动。可以用应用详情里的“实验”在自己的设备上验证。

### 1. 普通应用能不能查询别的应用的 `MANAGE_EXTERNAL_STORAGE`？

**能（Android 11+）。** `AppOpsService.checkOperation → checkOperationImpl` 只做 `verifyIncomingOp`（op 编号合法）和包可见性过滤（Android 15 的 `isIncomingPackageValid` 调用 `filterAppAccess`），**不调用** `verifyIncomingUid`，也不要求 `GET_APP_OPS_STATS` 之类的权限。声明了 `QUERY_ALL_PACKAGES` 后包是可见的，所以可以用 `AppOpsManager.unsafeCheckOpNoThrow("android:manage_external_storage", uid, pkg)` 查询：`MODE_ALLOWED` = 已开启，`MODE_DEFAULT` 时再看权限是否授予。如果某个 ROM 在这里抛出 `SecurityException`，界面会如实显示“已申请，需要 Shizuku 才能确认”。Android 10 没有这个权限。

### 2. 运行时权限对应的 appop 会不会被权限控制器改回？

**会（Android 11 起）。** `services/core/java/com/android/server/policy/PermissionPolicyService.java`：

- Android 11～15：启动时对**每个运行时权限**的 switch op 调用 `startWatchingMode(..., mAppOpsCallback)`；回调 `opChanged` 里会触发 `synchronizePackagePermissionsAndAppOps`，按权限授予状态把 uid 模式设回 `allow` / `foreground` / `ignore`；如果设完后实际模式仍不一致（说明有人改了包级别模式），还会执行 `setModeFromPermissionPolicy(..., opToDefaultMode(op))` 把包级别模式重置为默认（源码注释：“Work around incorrectly-set package mode”）。
- Android 10：只监听**有后台权限的**（定位）和**受限的**（存储）权限的 op；相机、麦克风的 appop 改成 `ignore` 后不会被这个机制立即改回。

因此本应用**用 `pm revoke` 收紧运行时权限**（撤销后权限控制器会把 appop 同步成 `ignore`，是持久有效的），只把 appop 用在没有对应运行时权限的场景（剪贴板、所有文件访问）。

### 3. 各种操作的实际效果

| 操作 | 效果 | 依据 |
|---|---|---|
| `pm revoke` 运行时权限 | 权限变为未授予，appop 被同步为 `ignore`；对方可以再次弹窗申请 | PermissionPolicyService 同步逻辑 |
| `READ_CLIPBOARD` = `ignore` | 对方读剪贴板时 `getPrimaryClip()` 返回 **null**（空剪贴板），**不报错** | `ClipboardService.clipboardAccessAllowed` 中 `noteOp(...) != MODE_ALLOWED` 时返回 false，`getPrimaryClip` 返回 null |
| `READ_CLIPBOARD` = `deny` | `noteOp` 抛 `SecurityException`，对方可能崩溃（本应用不使用） | `AppOpsManager.noteOp` 对 `MODE_ERRORED` 抛异常 |
| `MANAGE_EXTERNAL_STORAGE` = `deny` | 关闭所有文件访问，与系统设置里关掉开关相同 | 这是 appop 权限，PermissionPolicyService 只在应用不再申请它时才重置 |
| 运行时权限 op = `ignore`（实验） | Android 11+ 预期很快被改回 | 见第 2 点 |

另外：默认输入法读剪贴板同样要通过 appop 检查，所以自动收紧**永远不会处理已启用的输入法**（以及本应用和 Shizuku）。

### 4. `appops get` 输出格式

解析器（`core/.../appops/AppOpsParser.kt`）按 `AppOpsService.Shell` 中 `case "get"` 的实现编写：

- 先输出 uid 段（`getUidOps`，只包含**非默认**的 uid 模式），再输出包段；`Uid mode: ` 前缀**只出现在 uid 段第一行**。
- 每行格式：`OP: mode`，然后依次是 `; time=<时长> ago`、`; rejectTime=<时长> ago`、` (running)` 或 `; duration=<时长>`。
- Android 10 用 `!= 0` 判断有没有记录、`duration == -1` 表示 running；Android 11～15 用 `!= -1` 和 `isRunning()`。11～15 这段代码完全相同。
- 时长来自 `TimeUtils.formatDuration`：`0`，或 `+` / `-` 加 `Nd Nh Nm Ns Nms`（10 和 15 的实现相同）。
- 有效模式规则（`checkOperationUnchecked`）：非默认的 uid 模式优先于包模式。因为 uid 段先输出，所以**某个 op 第一次出现的模式就是有效模式**。

单元测试分别覆盖了 Android 10 和 11～15 两种格式。

### 5. 为什么不用 `ACTION_PACKAGE_ADDED`

Android 8 起，manifest 里静态注册的隐式广播（包括 `ACTION_PACKAGE_ADDED`）收不到。所以本应用用 **WorkManager 定期对比授权快照** + **每次打开 App 时检查一次** 来发现“新装的应用 / 新获得的授权”。后台进程也能拿到 Shizuku：Shizuku 服务端注册了 uid observer，应用进程（包括 WorkManager 拉起的后台进程）启动时就会把 binder 发过来，后台任务会等待最多 10 秒。

---

## 风险评级规则

规则写在纯函数 `core/src/main/kotlin/.../risk/RiskRater.kt` 里，有单元测试。分数相加，再映射为等级：

| 条件 | 分数 |
|---|---|
| 照片完全授权（或 Android 12- 的旧版存储权限） | +30 |
| 照片部分授权（仅所选） | +3 |
| 能读照片拍摄位置（且有照片访问） / 已授予但当前无照片访问 | +15 / +2 |
| 所有文件访问已开启 / 已申请但无法确认 | +40 / +10 |
| 后台定位 / 前台精确定位 / 前台大致定位 | +30 / +10 / +5 |
| 相机 / 麦克风 | +10 / +12 |
| 最近 7 天新装：有敏感访问 / 无 | +15 / +5 |
| 非系统应用：安装包侧载 / 由其他应用安装 / 来源未知 | +15 / +12 / +10 |
| （Shizuku）最近 24 小时读过剪贴板 | +8 |

等级：0 无风险，1–19 低，20–44 中，45–69 高，≥70 严重。
升级规则（直接定为“高”）：所有文件访问已开启；照片完全授权 + 后台定位；照片完全授权 + 非商店来源或最近新装。
“中风险”及以上且未被信任的应用会告警。

## 自动收紧

可选规则（`TighteningPlanner`，纯函数，有单元测试）：

- 撤销照片完全授权（`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`；Android 14+ 若还有“所选照片”授权则降级为部分访问）
- 撤销读取照片位置
- 撤销后台定位（保留前台定位）
- 关闭所有文件访问
- 屏蔽读取剪贴板（`ignore`）
- 撤销相机 / 麦克风

处理范围：

- **只处理新获得的授权**（默认）：和上次的快照对比，只处理新装的应用或刚拿到权限的应用。第一次检查只建立基线。
- **处理所有未信任应用**：每次都把所有未信任应用收紧到规则要求的状态。

跳过：信任列表、本应用、Shizuku、已启用的输入法、系统应用（可选包括）。在通知或日志里撤回一次自动收紧后，会为“该应用 + 该规则”加豁免，避免下次又被收紧（豁免可在策略页移除）。

---

## 安装 Shizuku

1. 从 [Shizuku 官网](https://shizuku.rikka.app/download/) 或 Google Play 安装 Shizuku。
2. 启动 Shizuku 服务：
   - **Android 11 及以上**：打开“开发者选项 → 无线调试”，在 Shizuku 里选择“通过无线调试启动”，按提示配对后启动。不需要电脑。
   - **Android 10**：需要用电脑连接一次 adb，执行 Shizuku 应用里显示的启动命令。
   - 已 root 的设备也可以直接用 root 启动（或使用 Sui）。
3. 打开隐私守卫，点“授权”，在 Shizuku 弹窗里允许。
4. **手机每次重启后都需要重新启动 Shizuku**（root 启动除外）。

## 下载 APK 并侧载安装

1. 打开仓库的 **Actions** 页面，选择最新一次成功的 **CI** 运行。
2. 在页面底部 **Artifacts** 里下载 `android-guard-release-<编号>`（配置了签名时）或 `android-guard-debug-<编号>`，解压得到 `.apk`。
3. 传到手机上，用文件管理器打开安装。第一次需要在系统提示里允许“安装未知应用”。
4. 打开后允许通知权限（自动收紧和风险提醒需要）。

> ⚠️ **关于 debug 包**：没有配置签名 Secrets 时，CI 构建的是 debug APK。GitHub Actions 每次运行都会生成一个新的 debug 签名，**不同次构建的 debug 包签名不同，不能覆盖安装**（只能先卸载，信任列表、策略和日志也会跟着丢失）。**长期使用请配置 release 签名**。debug 包的包名带 `.debug` 后缀，可以和 release 包共存。

## 配置 release 签名（GitHub Secrets）

1. 在自己电脑上生成 keystore（需要 JDK 自带的 `keytool`），**妥善保管，丢失后就不能再覆盖安装**：

   ```bash
   keytool -genkeypair -v -keystore release.jks -alias guard -keyalg RSA -keysize 4096 -validity 36500
   ```

2. 转成 base64：

   ```bash
   base64 -w 0 release.jks > release.jks.base64
   ```

   macOS 上用 `base64 -i release.jks -o release.jks.base64`。

3. 在仓库 **Settings → Secrets and variables → Actions → New repository secret** 添加 4 个 Secret：

   | Secret | 内容 |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | `release.jks.base64` 文件的全部内容 |
   | `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
   | `ANDROID_KEY_ALIAS` | 上面命令里的 alias（例如 `guard`） |
   | `ANDROID_KEY_PASSWORD` | key 密码（PKCS12 keystore 通常与 keystore 密码相同） |

4. 之后每次 push，CI 都会构建签名好的 release APK（开启 R8 压缩），产物名为 `android-guard-release-<编号>`，`versionCode` 等于 CI 运行编号，可以直接覆盖升级。

注意：来自 fork 的 PR 拿不到 Secrets，会构建 debug 包。

---

## 构建与测试

技术栈：Kotlin、Jetpack Compose、Material 3、Gradle Kotlin DSL + version catalog（`gradle/libs.versions.toml`），minSdk 29，compile/targetSdk 37。

```bash
./gradlew lint test assembleDebug
```

- `:core` 是纯 Kotlin/JVM 模块：风险评级、`appops` 输出解析、自动收紧策略判断、命令构造（含防注入校验）、日志模型。所有单元测试都在这里，用 JVM 跑，不依赖模拟器。
- `:app` 是 Android 应用：扫描、Shizuku（UserService 以 shell 身份执行 `pm` / `cmd appops`）、WorkManager、通知、Compose 界面。

CI（`.github/workflows/ci.yml`）在每次 push 和 PR 时运行：`lint` → `test` → 构建 APK → 用 `aapt2 dump permissions` 检查没有 `INTERNET` → 上传 APK。

## 隐私

- 不联网，没有 `INTERNET` 权限（构建时移除 + CI 检查）。
- 所有数据（信任列表、策略、快照、访问记录缓存、操作日志）只保存在应用私有目录，`allowBackup=false`。
- 通过 Shizuku 执行的命令只有 `pm grant/revoke` 和 `cmd appops get/set`，参数（包名、权限名、op 名、模式）都经过白名单格式校验，杜绝命令注入。

# AGENTS.md

本文件为 AI 编码代理（Agent）提供本仓库的导航与工作规范。请先通读本文件再开始改动。

## 项目简介

**EasyssTun** 是一个轻量级 Android VPN 客户端（fork 自 [bingooo/EasyssTun](https://github.com/bingooo/EasyssTun)），基于 [easyss](https://github.com/nange/easyss) 代理协议。

流量链路：

```
设备流量 → Android TUN 虚拟网卡 → tun2socks（hev-socks5-tunnel，C 实现）→ SOCKS5 → libeasyss 加密 → 远程 easyss 服务器
```

## 技术栈与环境要求

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin（App 层）+ C/C++（native 层，以预编译 AAR 引入） |
| 构建 | Gradle（wrapper 9.7.1 / AGP 9.3.0），无需本地 NDK |
| JDK | 25（CI 使用 temurin 25） |
| Kotlin | 2.3.21 |
| Build Tools | 36.0.0 |
| minSdk / targetSdk / compileSdk | 33 / 37 / 37 |
| ABI | 默认仅 `arm64-v8a`（`-PabiOverride=x86_64` 可覆盖，供模拟器使用） |
| UI 框架 | View 体系 + ViewBinding + Navigation Component（**不是 Compose**） |
| 主要依赖 | kotlinx-serialization、kotlinx-coroutines、Material、Robolectric（测试） |

## 常用命令

所有构建均通过根目录 `Makefile` 封装：

```bash
make build      # ./gradlew assembleDebug —— 调试构建
make release    # ./gradlew assembleRelease —— 发布构建（需签名配置）
make lint       # ./gradlew lintDebug
make test       # ./gradlew test —— 单元测试（Robolectric，本地 JVM 运行）
make check      # lint + test（CI 执行的就是这个）
make worktree-clean  # 删除 .agents/worktrees/ 下的工作树及其分支（见「Git 工作流」）
make clean
```

改动代码后至少执行 `make check` 验证（lint 有告警先修复）。

**Windows**：`Makefile` 会按平台自动选择 Gradle wrapper（Windows 统一经 `cmd /c gradlew.bat` 调用，兼容 cmd.exe、PowerShell 与 Git Bash/MSYS；类 Unix 用 `./gradlew`），因此 `make check` 在 Windows 上同样可用（需自行安装 GNU Make，如 `choco install make`、`scoop install make` 或 MSYS2 的 `make`）。环境里确实没有 make 时，直接在仓库根目录跑等价命令：`.\gradlew.bat lintDebug`（PowerShell / cmd）或 `./gradlew lintDebug`（Git Bash / WSL / macOS / Linux）。

## 仓库结构与关键文件

```
version.properties            # ★ 版本号唯一来源：versionCode / versionName / libeasyssVersion / hevSocks5TunnelVersion（发版需手动递增）
app/build.gradle              # 构建配置：签名、版本读取、ABI 拆分、APK 命名、两个 AAR 的自动下载
Makefile                      # 构建入口（见上）
keystore.properties / local.properties  # 本地签名与 SDK 路径（均 gitignore；签名模板见 keystore.properties.example）
app/src/main/java/com/easysstun/
  EasyssVpnService.kt         # ★ 核心：VpnService 前台服务，建 TUN 网卡、启动 native 隧道、通知栏管理
  VpnState.kt / VpnController.kt  # ★ VPN 状态的唯一真相来源：进程级 StateFlow + connect/disconnect/switchTo 命令
  MainActivity.kt             # 主 Activity：Navigation 宿主、通知权限申请、更新弹窗宿主
  MainFragment.kt             # ★ 主界面：渲染 VpnState、连接/取消、服务器切换、统计与更新卡片
  ServerSpinnerController.kt  # 首页服务器下拉框（列表重建、active 选中回填、用户选择回调）
  ServerProfileActivity.kt / SpinnerOption.kt  # 服务器配置档编辑页（ViewBinding）与「显示文案/存储值」双数组下拉框
  SettingsFragment.kt / ServerProfileAdapter.kt  # 设置页：RecyclerView + ListAdapter 的服务器列表
  Profile.kt / ProfileSerializers.kt  # 服务器配置数据模型（端口/超时/QUIC 为类型化字段，含旧 JSON 迁移）
  Pref.kt                     # 仅键值访问（偏好读写；不含档案、分应用、配置文件注册表）
  ProfileStore.kt             # ★ 服务器档案仓库：CRUD + active id 不变量 + changes 变更事件
  AppSelectionStore.kt        # 分应用选择（按代理模式分开存储 + 旧 key 迁移）
  ProfileConfigWriter.kt      # 原生配置落盘（CA/规则/tproxy；返回 Result，内容未变不重写）
  ConfigChangeNotifier.kt     # 配置变更的唯一广播出口（运行中的隧道据此重载）
  AppListFragment.kt / AppListAdapter.kt  # 分应用代理选择列表（ViewBinding；标签/图标加载随 ViewHolder 回收取消）
  LogFragment.kt              # 运行日志查看（ViewBinding + ListAdapter，按页加载历史；LogViewModel 也在此文件）
  LogStore.kt                 # 日志管道：LogParser（logcat 行解析）+ LogBuffer + LogCapture + 进程级 logcat 采集
  LogTags.kt                  # 日志 tag 集中定义；LogStore 的 `logcat -s` 过滤列表由 LogTags.ALL 生成（VERSION_LOG_TAG 在 VersionInfo.kt）
  VersionInfo.kt              # 启动版本横幅（App 版本 + libeasyss 版本）与 VERSION_LOG_TAG
  Stats.kt / StatsBinder.kt   # ★ 本地 stats 端点客户端/快照（首页与通知栏共用）及统计卡片渲染
  UpdateManager.kt / UpdateSource.kt      # ★ 应用内更新：节流检查、下载状态机、GitHub API 与本地代理回退
  UpdateRelease.kt / UpdateVersion.kt     # Release 解析/选包、版本比较（rc 数字按数值比较）
  UpdateInstaller.kt / UpdateDialogs.kt   # 签名校验、FileProvider 安装 intent、更新弹窗
  UpdateCardView.kt           # 首页统计卡片下方的「软件更新」卡片（版本号、自动检查开关、检查/下载）
  ServiceReceiver.kt          # 开机自启广播接收
  AppJson.kt                  # 共享 kotlinx.serialization Json 配置（档案存储、Release 解析）
  AppState.kt / TunnelState.kt / FormatUtils.kt / EasyssTunApplication.kt  # 辅助类（AppState=前后台，TunnelState=隧道就绪）
app/src/main/res/xml/         # file_paths（FileProvider：cacheDir/updates）、network_security_config（仅 127.0.0.1/localhost 明文）、backup_rules / data_extraction_rules（排除 sharedpref）
app/libs/                     # ★ 本地 AAR 依赖（均不入库，首次构建自动下载）
  libeasyss.aar               #   easyss 代理原生库
  hev-socks5-tunnel.aar       #   tun2socks 实现（上游 heiher/hev-socks5-tunnel 预编译，含 4 ABI .so + classes.jar + proguard.txt）
app/src/test/                 # Robolectric 单元测试（见「测试」）
.github/workflows/            # ci.yml（push/PR 到 main 跑 make check）、release.yml（v* 标签构建签名 APK 并发布）
```

## 构建机制要点

- **libeasyss.aar 自动下载**：本地不存在时，`app/build.gradle` 按 `version.properties` 的 `libeasyssVersion`（当前 `v3.0.0`）从 `nange/easyss` 的 GitHub Release 下载到 `app/libs/`；未锁定版本则调 GitHub API 取最新版。删除该文件即可触发重新下载。
- **hev-socks5-tunnel.aar 自动下载**：机制同上，版本由 `hevSocks5TunnelVersion`（当前 `2.19.3`）锁定，来自**上游** `heiher/hev-socks5-tunnel`（上游自 `2.18.0` 起官方发布 AAR，`2.17.1` 及更早没有 aar 资产）。AAR 自包含：4 个 ABI 的 `libhev-socks5-tunnel.so`、`classes.jar`（绑定类 `hev.htproxy.TProxyService`，静态 native 方法 + 静态块 `System.loadLibrary`）与 `proguard.txt`。升级 tun2socks：更新 `hevSocks5TunnelVersion` 并删除本地 aar（新 AAR 必须带 `classes.jar`，否则 Kotlin 编译期即失败）。
- **tun2socks JNI 契约（勿改）**：native 侧固定注册到 `hev/htproxy/TProxyService` 的类名与方法签名。App 内直接 `import hev.htproxy.TProxyService` 调用 `TProxyStartService`/`TProxyStopService`，因此本项目的 VpnService 类叫 `EasyssVpnService`（不能叫 `TProxyService`，否则遮蔽 import）。确需改类名/签名，必须用 `-DPKGNAME/-DCLSNAME` 重编 .so；契约由 `TProxyJniContractTest` 守护。
- **签名**：优先读本地 `keystore.properties`；CI 用 `KEYSTORE_BASE64`（release.yml 解码成 `ci-keystore.jks`）配合 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。两者都没有时 release 构建产出未签名 APK。
- **debug 变体**：包名后缀 `.debug`，应用名带 "(Debug)"，可与正式版共存。
- **APK 命名**：`EasyssTun_v{versionName}_{versionCode}_{abi}_{variant}_{yyyyMMdd}.apk`。
- **网络安全与备份**：全局 `usesCleartextTraffic` 已移除，`network_security_config.xml` 仅对 `127.0.0.1` / `localhost` 放行明文（本地 stats/更新代理），新增其他明文端点必须显式加入该配置。服务器档案的密码以明文存于默认 SharedPreferences，`backup_rules.xml` 与 `data_extraction_rules.xml` 均排除 `sharedpref` 域，勿把该域加回备份。
- **应用内更新（勿破坏的对外契约）**：App 通过 `https://api.github.com/repos/nange/EasyssTun/releases/latest` 检查更新（`UpdateRelease.kt`），依赖以下约定：
  1. APK 命名必须保持上面的格式（正则见 `UpdateRelease.kt`）；只接受 `release` 变体与设备支持的 ABI，且资产名里的 `{versionName}` 必须等于 release tag 去掉前缀 `v`。
  2. `versionCode` 必须单调递增，并与 `versionName` 同时更新：两者都比当前安装版本新才提示，否则按「发布信息不一致」拒绝安装。
  3. Release **不可**标记为 pre-release 或 draft，否则 `/releases/latest` 会跳过它，自动更新停在旧版本。
  4. 下载到 `cacheDir/updates`，经 FileProvider（authority `${applicationId}.fileprovider`）交给系统安装器；需要 `REQUEST_INSTALL_PACKAGES` 与「安装未知应用」授权，且必须与已安装版本使用同一签名 key（换 key 后用户只能手动卸载重装）。
  5. VPN 运行时检查/下载优先走 libeasyss 本地 HTTP 代理 `127.0.0.1:{socksPort+1000}`，失败回退直连（App 自身流量被排除在 TUN 外）；debug 构建禁用更新。
  6. 自动检查由隧道就绪触发：`VpnController` 进入 `Running` 时把 `TunnelState.ready` 置 true（服务在 `waitForSocksReady` 与 `TProxyStartService` 成功后上报 `onServiceRunning`），`Pref.userWantsVpn` 只代表用户意图，不能用来判断代理可用。冷启动时隧道未就绪则挂起，就绪事件到达即检查，最多等 `LAUNCH_FALLBACK_DELAY_MS`（60s）后直连兜底（覆盖从不连 VPN 的用户）；检查失败不写 `updateLastCheckAt`，因此不消耗 24h 窗口。
  修改资产命名或发布方式时，必须同步更新 `UpdateRelease.kt` 的正则、`UpdateReleaseTest.kt` 与本段文档。
- **Gradle wrapper 升级**：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties` 四个文件必须同版本、**一起提交**（wrapper jar 入库是 Gradle 官方要求；勿在 `.gitignore` 里加裸 `wrapper` 规则，它会匹配 `gradle/wrapper/` 并让 `git add` 直接报错退出）。升级必须跑**两次** wrapper 任务：先 `./gradlew wrapper --gradle-version <新版本>`（由当前版本执行，只改 properties），再执行一次 `./gradlew wrapper`（此时由新版本执行，刷新 jar 与脚本）。脚本/jar 到底变没变一律以 `git diff` 为准。收尾校验：`./gradlew --version`，并将 `shasum -a 256 gradle/wrapper/gradle-wrapper.jar` 与 `https://services.gradle.org/distributions/gradle-<版本>-wrapper.jar.sha256` 比对。**切勿合并外部 PR 提交的 wrapper jar**，务必用可信发行版按上述步骤本地重新生成。

## 测试

- 单元测试位于 `app/src/test/java/com/easysstun/`，基于 JUnit4 + Robolectric，`make test` 本地即可跑，不需要设备/模拟器。
- 新增纯逻辑（工具类、序列化、状态管理）时应附带单元测试，测试类按被测组件命名（`XxxTest`）。
- 现有覆盖：应用内更新（版本比较/选包/状态机/安装校验）、档案与配置层、VPN 状态机与首页状态、统计与格式化、表单/列表/日志。
- 两处契约必须随实现同步：`TProxyJniContractTest`（AAR 的 JNI 类名与方法签名）、`UpdateReleaseTest`（发布资产命名与选包）。

## 工作规范

上文是**项目事实**（结构、构建、契约）；本节是 **Agent 行为约定**。两者冲突时以用户当次指令为准。

### 交互约定

- **始终使用简体中文回复用户**；代码、标识符、commit message、PR 标题保持英文。
- **不主动提交**：用户没有明确要求提交代码前，不要 `git commit`、`git push` 或创建 PR。改动完成后说明改了什么、怎么验证，把提交时机留给用户。
- 同理不做破坏性 git 操作（`reset --hard`、强制 checkout 覆盖工作区、删除他人分支等），除非用户明确要求。

### 代码与注释

代码规范：

- Kotlin 代码风格：`official`（见 `gradle.properties`）。
- 数据序列化统一用 `kotlinx.serialization`，不要引入 Gson/Moshi。
- UI 一律 View + ViewBinding（含自定义 View 内部），不要用 `findViewById`；修改布局后通过生成的 Binding 类访问。
- 列表用 `RecyclerView` + `ListAdapter`/`AsyncListDiffer`；异步加载的图标/文案必须绑定到 ViewHolder，并在回收时取消。
- 异步任务使用协程（现有代码集中在 `Dispatchers.IO`），不要引入新的线程库。
- 字符串资源：默认英文在 `res/values/`，中文在 `res/values-zh/`，新增用户可见文案需两处同步（`translatable="false"` 的纯数值提示除外）。
- minSdk 为 33，可以使用较新的 Android API，无需做低版本兼容分支。
- 配置层职责：`Pref` 只做键值访问；服务器档案一律走 `ProfileStore`，分应用选择走 `AppSelectionStore`，原生配置文件走 `ProfileConfigWriter`。配置 setter 不会自动重启隧道，需要运行中的隧道重载时由调用方显式调用 `ConfigChangeNotifier.notifyChanged()`。
- `Profile` 的端口/超时/QUIC 字段是类型化的（`Int?` / `Boolean`），旧 JSON 由 `LenientIntSerializer` / `LenientBooleanSerializer` 兼容，不要退回字符串字段。
- native 层以 `app/libs/` 下的预编译 AAR（libeasyss、hev-socks5-tunnel）引入，本仓库无 C 源码与 NDK 构建；如需修改 native 行为，到对应上游/fork 仓库改动并发布新 AAR 后，更新 `version.properties` 锁定版本。
- 日志：tag 集中定义在 `LogTags`，新增 logger 必须把 tag 加进 `LogTags.ALL`（否则 `logcat -s` 过滤会丢弃该 tag）；高频状态变化用 `Log.d`，不要用 INFO 刷屏。

注释约定：只写「读代码看不出来、写错会静默出错」的两类事实：

1. **顺序 / 锁 / 生命周期约束**——例如必须先 `stop()` 再 `release()`、回调在哪个线程触发、字段在哪一阶段才有效。
2. **外部系统行为或跨包契约**——例如服务端字段语义、AAR/JNI 的类名与方法签名、系统 API 的隐式副作用。

不要写：代码演进史（看 `git log` 与 commit message）、枚举式清单（新增一个方法就变成错误文档）、设计取舍的长篇解释（写进 commit message 或本文件）。单个注释块不超过 3 行；函数名、类名已经说清楚的不要再加 godoc 式复述。

### Git 工作流

**worktree**：仅当当前处于主分支（本仓库为 `main`）时，代码改动才先建 worktree 再改；已经在功能分支、用户指定的分支或工作树上时直接改。

- 目录固定为 `.agents/worktrees/<name>`，`<name>` 可用 `type/name` 分层（如 `feat/app-list-filter`，对应分支名相同）；该目录不入库（`.gitignore` 已忽略），不要提交其中的内容。
- 从当前 HEAD 或用户指定的 base 新建分支。
- 清理统一走 `make worktree-clean`：删除 `.agents/worktrees/` 下的工作树及其检出的分支（分支强制删除）。不要手动 `rm -rf`，否则 `git worktree list` 里会残留失效条目。

### 提交信息格式

遵循 Conventional Commits 风格，单行、全英文、小写开头、祈使语气、结尾不加句号：

```
<type>: <简短英文描述>
```

常用 `type`（与仓库现有提交历史保持一致）：

| type | 用途 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `docs` | 文档变更 |
| `build` | 构建 / 依赖 / 环境调整 |
| `chore` | 版本号递增、子模块指针更新等杂项 |
| `tweak` | 小幅行为或展示微调 |

示例（摘自仓库历史）：

- `feat: add option to show/hide system apps in app proxy list`
- `fix: keep VPN running when switching server on home page`
- `docs: add AGENTS.md agent guide`
- `chore: bump v3.0.0-rc15.1`

### 提交 PR

- 不要直接向 `main` 主分支提交 PR 改动：从最新 `main` 切出**单独的功能分支**（英文短横线命名，如 `feat/app-list-filter`、`fix/tun-fd-leak`），完成后推送该分支并创建 PR。
- PR 标题用英文，采用 `<type>: <description>` 格式。
- PR 描述用中文，包含：改动动机、具体内容、影响范围、验证方式（如 `make check` 通过、真机测试结果）。
- 合入 `main` 后 CI 自动执行 `make check`；提 PR 前先在本地跑一遍。

### Review PR

目标简洁高效，不逐段复述 diff。

1. **先确认代码是否已在本地**：查 `git worktree list`、`.agents/worktrees/`、`git branch -r`、`git log --all` 中是否有该 PR 的 head 提交。命中就直接在对应工作树/分支评审；需要最新提交时只 `git fetch`，不要重新 clone。
2. 本地确实没有（如外部 fork 的 PR）才 clone 或下载补丁。
3. 从 PR 描述与改动范围入手，只输出影响**正确性、跨包契约、明显维护成本**的问题；格式与命名偏好不提。
4. 结论先行：每个问题给出文件/行号、问题本身、判断依据（为什么错、会怎么坏）。不为了凑数提建议。

## 版本发布流程

1. 更新 `version.properties`（递增 `versionCode`、`versionName`，必要时同步 `libeasyssVersion` / `hevSocks5TunnelVersion`）。
2. 合并到 `main` 分支（CI 会跑 `make check`）。
3. 打 `v*` 格式的 git 标签并推送，Release 工作流自动构建签名 APK 并创建 GitHub Release。

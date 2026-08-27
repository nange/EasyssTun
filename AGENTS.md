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
|---|---|
| 语言 | Kotlin（App 层）+ C/C++（native 层） |
| 构建 | Gradle（AGP 9.3.0）+ NDK Build |
| JDK | 25（CI 使用 temurin 25） |
| Kotlin | 2.3.21 |
| NDK | 25.0.8775105，Build Tools 36.0.0 |
| minSdk / targetSdk / compileSdk | 33 / 37 / 37 |
| ABI | 仅 `arm64-v8a` |
| UI 框架 | View 体系 + ViewBinding + Navigation Component + Preference（**不是 Compose**） |
| 主要依赖 | kotlinx-serialization、kotlinx-coroutines、Material、Robolectric（测试） |

## 常用命令

所有构建均通过根目录 `Makefile` 封装：

```bash
make build      # ./gradlew assembleDebug —— 调试构建
make release    # ./gradlew assembleRelease —— 发布构建（需签名配置）
make lint       # ./gradlew lintDebug
make test       # ./gradlew test —— 单元测试（Robolectric，本地 JVM 运行）
make check      # lint + test（CI 执行的就是这个）
make clean
```

改动代码后至少执行 `make check` 验证。

## 仓库结构与关键文件

```
version.properties            # ★ 版本号唯一来源：versionCode / versionName / libeasyssVersion（发版需手动递增）
app/build.gradle              # 构建配置：签名、版本读取、ABI 拆分、APK 命名、libeasyss.aar 自动下载
Makefile                      # 构建入口（见上）
keystore.properties           # 本地签名配置（已 gitignore，模板见 keystore.properties.example）
local.properties              # 本地 SDK/NDK 路径（已 gitignore）
app/src/main/java/com/easysstun/
  TProxyService.kt            # ★ 核心：VpnService 前台服务，建 TUN 网卡、启动 native 隧道、通知栏管理
  MainFragment.kt             # ★ 主界面与连接控制逻辑（最大的一个文件）
  ServerProfileActivity.kt    # 服务器配置档编辑页
  Profile.kt                  # 服务器配置数据模型（kotlinx-serialization）
  Pref.kt                     # SharedPreferences 封装（设置持久化）
  AppListFragment.kt / AppListAdapter.kt  # 分应用代理选择列表
  LogFragment.kt              # 运行日志查看
  SettingsFragment.kt         # 偏好设置页
  ServiceReceiver.kt          # 开机自启广播接收
  AppState.kt / FormatUtils.kt / EasyssTunApplication.kt  # 辅助类
app/src/main/jni/
  Android.mk / Application.mk # NDK 构建入口，编译 hev-socks5-tunnel
  hev-socks5-tunnel/          # ★ git 子模块（tun2socks 实现，来自 nange 的 fork）
app/libs/libeasyss.aar        # ★ easyss 代理原生库（不入库，首次构建自动下载）
app/src/test/                 # Robolectric 单元测试
.github/workflows/
  ci.yml                      # push/PR 到 easyss 分支时跑 make check
  release.yml                 # 打 v* 标签时构建签名 APK 并发布 GitHub Release
```

## 构建机制要点

- **libeasyss.aar 自动下载**：本地不存在时，`app/build.gradle` 会按 `version.properties` 中的 `libeasyssVersion`（如 `v3.0.0-rc10`）从 `nange/easyss` 的 GitHub Release 下载到 `app/libs/`；未锁定版本则调 GitHub API 取最新版。删除该文件即可触发重新下载。
- **子模块**：hev-socks5-tunnel 以 git 子模块形式集成。克隆需 `--recursive`，已有仓库执行 `git submodule update --init --recursive`。**不要直接修改子模块内容**，除非明确要求更新其指向。
- **签名**：优先读本地 `keystore.properties`；CI 走 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 环境变量。两者都没有时 release 构建产出未签名 APK。
- **debug 变体**：包名后缀 `.debug`，应用名带 "(Debug)"，可与正式版共存。
- **APK 命名**：`EasyssTun_v{versionName}_{versionCode}_{abi}_{variant}_{yyyyMMdd}.apk`。

## 编码规范

- Kotlin 代码风格：`official`（见 `gradle.properties`）。
- 数据序列化统一用 `kotlinx.serialization`，不要引入 Gson/Moshi。
- UI 使用 View + ViewBinding（`buildFeatures.viewBinding = true`），修改布局后通过生成的 Binding 类访问，不要用 `findViewById`。
- 异步任务使用协程（现有代码集中在 `Dispatchers.IO`），不要引入新的线程库。
- native 层（`app/src/main/jni/`）除非明确要求，一般只需通过 NDK 构建配置调整，避免手写 C 代码。
- 字符串资源：默认英文在 `res/values/`，中文在 `res/values-zh/`，新增用户可见文案需两处同步。
- minSdk 为 33，可以使用较新的 Android API，无需做低版本兼容分支。

## 测试

- 单元测试位于 `app/src/test/java/com/easysstun/`，基于 JUnit4 + Robolectric，`make test` 本地即可跑，不需要设备/模拟器。
- 新增纯逻辑（工具类、序列化、状态管理）时应附带单元测试。

## 版本发布流程

1. 更新 `version.properties`（递增 `versionCode`、`versionName`，必要时同步 `libeasyssVersion`）。
2. 合并到 `easyss` 分支（CI 会跑 `make check`）。
3. 打 `v*` 格式的 git 标签并推送，Release 工作流自动构建签名 APK 并创建 GitHub Release。

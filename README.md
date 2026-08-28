# 脚本终端（ShellDeck）

一款轻量、原生的 Android Shell 脚本启动器，让你可以在手机上保存、整理和运行 `.sh` 脚本，并通过独立控制台查看输出、发送输入。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> 当前版本：`1.6.8`（versionCode 28）
>
> 当前原生 PTY 库仅提供 `arm64-v8a`，因此 APK 适用于 64 位 ARM Android 设备。

## 项目特点

### 脚本管理

- 从 MT 管理器选择 `.sh` 文件；未安装 MT 时自动回退到 Android 系统文件选择器。
- 导入后保存脚本副本，不依赖文件选择器授予的临时读取权限。
- 支持自定义显示名称，不修改原文件名。
- 长按卡片左侧拖动把手调整顺序，排序会永久保存。
- 点击卡片不会误启动脚本，只有右侧运行按钮会执行。
- 可通过 `脚本设置 → 更多 → 更新脚本源` 重新选择新版文件，同时保留脚本显示名称、列表排序位置、脚本 ID、私有工作目录和输入记忆开关。

### 独立控制台与后台运行

- 每次启动都会创建独立的 PTY、控制台和运行快照，不复用其他脚本的通道。
- 最多同时维护 4 个脚本通道，避免意外启动过多任务。
- 实时显示标准输出和标准错误，并支持向脚本发送 stdin 输入，适用于 Shell 的 `read` 等交互命令。
- 控制台输入发送后会自动保持输入焦点，方便连续操作。
- “通道”页集中显示正在启动和运行的任务，可快速返回对应控制台或关闭显示通道。
- 前台服务通知可直接回到通道列表，降低后台运行时被系统回收的概率。

### 固定工作目录

每个已保存脚本都有独立且固定的应用私有工作目录和 `HOME`。脚本写入其中的配置、缓存或登录状态会保留到下次运行，所以依赖本地配置文件的脚本无需每次重新初始化。

更新脚本源不会改变这个工作目录；从列表移除脚本时，应用内的导入副本和相关输入记录会被清理，但不会删除手机上的原始脚本文件。

### 记忆并自动输入

该功能默认关闭，可在单个脚本的三点菜单中开启。

- 第一次运行时，应用记录“脚本提示语 + 用户输入”。
- 下次运行同一版本脚本时，只有检测到对应提示或新的输出阶段后才发送下一项，避免一次性提前写入导致输入失效。
- 自动输入期间手动发送任意内容会立即接管本次会话，并停止剩余自动输入。
- 空回车也可以被记录和回放。
- 输入记录同时支持普通模式与 Root 模式。
- 输入记录按脚本内容的 SHA-256 隔离；脚本内容更新后会重新学习，防止旧参数错误地传给新版脚本。
- 单个脚本最多保存 32 项输入。请勿对会回显或处理明文密码的脚本开启此功能。

### 普通 / Root 模式

- **普通模式**：使用 `/system/bin/sh` 执行，权限与应用自身相同。
- **Root 模式**：先通过 `su -c id` 检查 Root 授权，再在 Root PTY 中执行脚本。
- 模式选择会保存；Root 状态使用浅红色提示，避免和普通模式混淆。
- 文本脚本由系统 Shell 运行；若文件实际为 ELF 可执行文件，则在 PTY 中直接启动。

### 进程监控

- 在 Root 环境下读取进程列表，显示应用图标、应用名称、包名、PID、CPU 和内存信息。
- 支持按 CPU 或内存占用降序排列，也可按名称、包名或 PID 搜索。
- 进程页仅在可见时每 3 秒刷新一次，离开页面后立即停止刷新。
- 包名和应用图标在后台加载，避免冷启动进入进程页时阻塞主线程。
- 只有与本应用 `files/runs/` 运行快照和启动时间严格匹配的脚本主进程可以结束。
- Android 系统、内核、Root 服务、厂商核心、第三方应用、未知进程和本应用自身均为只读，降低误结束系统进程的风险。

## 重要安全说明

本应用是脚本启动器，不是安全沙箱。

- Root 模式下，脚本拥有完整 Root 权限。脚本中的 `reboot`、磁盘写入、进程结束、权限修改或资源耗尽等行为，应用无法隔离或撤销。
- 仅运行你理解并信任其来源的脚本；运行前请自行审阅内容。
- “结束当前通道”仅关闭当前控制台的显示、输入连接和通知，**不会**向脚本发送 `Ctrl+C`、`TERM` 或 `KILL`。不依赖控制台的脚本可继续在后台运行。
- 如需结束残留脚本，请前往“进程”页操作；该页面仍会进行严格的身份校验，不允许任意结束其他进程。
- 输入记忆数据保存在应用私有存储中，但并非专用密码保险箱。不要用它保存高价值密码、恢复短语或长期密钥。
- Root 设备本身具有更高风险，使用前建议准备可靠备份。

## 系统要求

- Android 8.0（API 26）或更高版本；
- 64 位 ARM 设备（`arm64-v8a`）；
- Java 17；
- Android SDK 35；
- Gradle 8.7；
- Root 功能需要设备已经安装可用的 `su` 管理方案；普通模式不需要 Root；
- MT 管理器为可选依赖，支持 `bin.mt.plus.canary` 与 `bin.mt.plus`。

## 构建项目

### 使用 Gradle Wrapper

Windows PowerShell：

```powershell
git clone https://github.com/liujamsa/terminal-runner.git
cd terminal-runner
./gradlew.bat assembleDebug
```

macOS / Linux：

```bash
git clone https://github.com/liujamsa/terminal-runner.git
cd terminal-runner
./gradlew assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

构建 Release APK：

```powershell
./gradlew.bat assembleRelease
```

当前 `release` 构建为了本地直接安装使用调试签名。正式分发或上架前，请配置自己的 release keystore，切勿提交签名文件和密码。

### 安装到设备

确保 ADB 已连接：

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 基本使用

1. 点击脚本页右上角 `+`。
2. 在 MT 管理器或系统文件选择器中选择脚本。
3. 如有需要，通过三点菜单修改显示名称、开启输入记忆或更新脚本源。
4. 在顶部选择“普通”或“Root”模式。
5. 点击脚本右侧运行按钮进入独立控制台。
6. 在底部输入框发送参数或交互内容。
7. 从“通道”页管理仍在显示的控制台，从“进程”页检查脚本残留及资源占用。

## 测试脚本

仓库提供 [`samples/interactive-demo.sh`](samples/interactive-demo.sh)。它不会修改系统，只会在该脚本自己的私有 `HOME` 中保存一行名称，并分阶段请求输入，可用于验证工作目录持久化、普通文本输入、空回车以及按提示记忆并自动输入。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE` | 在后台保持独立脚本通道及通知 |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ 前台服务类型声明 |
| `POST_NOTIFICATIONS` | 显示脚本运行与通道汇总通知 |
| `QUERY_ALL_PACKAGES` | 将 Root 进程映射为应用名称/图标，并识别需要保护的系统与第三方应用 |

应用未申请存储管理权限。脚本通过系统文件选择器导入到应用私有目录；Root 脚本自身可以访问的内容由设备 Root 环境决定。

## 项目结构

```text
app/src/main/java/com/local/shelldeck/
├── MainActivity.java              # 脚本列表、导入、排序、模式与脚本设置
├── TerminalActivity.java          # 单个脚本的交互控制台
├── RunningSessionsActivity.java   # 运行通道列表
├── ProcessMonitorActivity.java    # Root 进程监控界面
├── ScriptExecutionService.java    # 前台服务和会话生命周期
├── ExecutionSession.java          # PTY、进程、输出与自动输入逻辑
├── ScriptStore.java               # 脚本副本、元数据与工作目录
├── InputMemoryStore.java          # 输入记忆存储
├── RootProcessRepository.java     # Root 进程采样与安全拦截
├── AppTabs.java                   # 底部导航
└── Ui.java                        # 统一轻量 UI 样式
```

## 第三方组件

ARM64 PTY 支持使用 [FuryTerminal](https://github.com/FuryForm/fury_terminal) `v0.8.0` 的 `libterm.so`。固定版本、提交号和文件 SHA-256 记录在 [`THIRD_PARTY_NOTICES.txt`](app/src/main/assets/THIRD_PARTY_NOTICES.txt) 中。

## 参与开发

欢迎提交 Issue 和 Pull Request。修改涉及 Root、进程结束、通道生命周期或脚本存储时，请优先保证系统安全和向后兼容，并在提交说明中写明测试设备与 Android 版本。

## 开源许可

本项目采用 [MIT License](LICENSE) 开源。你可以自由使用、复制、修改、合并、发布、分发、再许可及商业使用，只需保留原始版权与许可声明。

# DSH Android

在 Android 设备上原生运行 DeepSeek Harness（dsh），通过无障碍服务读取屏幕并执行点击、输入、滑动、截图和系统级命令。

项目由三部分组成：

- **DSH Bridge APK**：Android 无障碍服务，监听 `127.0.0.1:17654`
- **dsh 插件**：注册 `android_state`、`android_act`、`android_apps`、`android_screenshot`、`android_virtual_screen`、`android_shell`、`android_health`
- **安装/兼容层**：在 Termux 中安装 Node、dsh 和原生依赖；root 走 su，no-root 可走 Shizuku

> Developer preview。dsh、Termux、Android 版本变化都可能导致脚本需要调整。

## 功能

- 读取当前无障碍树：文本、contentDescription、resource id、bounds、可点击/可编辑/可滚动状态
- 点击、长按、setText、滚动、滑动、全局返回/主页/最近任务
- 通过无障碍服务启动应用、打开 URL；root 模式也可用 `monkey`/`am`
- 无障碍截图，保存为 JPEG；附件存储可用时可附加到模型请求
- root 模式额外提供 `android_shell`：`input keyevent`、`screencap`、`am`、`pm`、`settings`、`svc`、`dumpsys` 等
- 安装脚本自动完成 Termux、Node、dsh、koffi、node-pty、sharp WASM 和插件部署
- 无 root 模式只安装 Termux 侧运行时；不安装 root shell、su、pm/settings/appops 等 root 专属能力
- 虚拟屏幕（root 或 Shizuku）：创建 headless 第二显示，dsh 在虚拟屏运行/操作应用，机主继续使用物理屏

## 架构

```text
┌──────────────────────────────────────────────┐
│ Android 设备                                  │
│                                              │
│  DSH Bridge APK (普通 App)                   │
│  AccessibilityService + HTTP 127.0.0.1:17654 │
│         ▲                                    │
│         │ JSON/HTTP                          │
│  dsh (Node 24, root 或 Termux 用户)          │
│  Web UI 监听 -HOST:-PORT，默认 127.0.0.1:3080 │
│         ▲                                    │
│         │ Cordis patch                       │
│  ~/.dsh/plugins/android-tool.mjs             │
│  ~/.dsh/plugins/android-compat.mjs           │
└──────────────────────────────────────────────┘
```

dsh 进程通过 `~/.dsh/compat/bootstrap.mjs` 在启动时替换两个 Android 不兼容模块：

- `@deepseek-ai/node-addon-system/flock` → koffi 调用 bionic `flock(2)`
- `node-addon-require-builtin` → `--expose-internals` shim

dsh 源码不需要修改。

## 悬浮 DSH

主界面点击 **Start floating DSH** 后，会显示一个可拖动的 `DSH` 悬浮球。点击悬浮球展开对话窗口：

- 显示当前 DSH Web UI 的对话、运行状态和审批卡片，并跟随系统深色/浅色模式
- 可直接在窗口内输入和发送消息
- 顶部按钮：最小化、最大化/还原、关闭
- 拖动窗口右下角可调整大小；旋转屏幕或分辨率变化时自动适配
- 主界面滑块可调节悬浮球大小（36–96dp）
- DSH 运行期间悬浮球统一显示转圈，空闲时显示图标
- 主界面 **Start/Stop floating DSH** 控制悬浮服务；通知 **Show/Hide** 只切换悬浮球显示
- 点击前台通知可隐藏或显示悬浮球；通知隐藏时通知保留，`X` 关闭服务并移除通知
- 最小化后再展开会保留之前的小窗尺寸或最大化状态
- 最小化时，出现审批或当前思考结束会自动展开；因审批自动展开的，审批结束后自动收起
- 审批卡片显示时可直接允许或拒绝；也可以先点卡片，再按 Enter 允许

首次使用需要授予 **显示在其他应用上层** 权限；Android 13+ 还会请求通知权限，用于保持前台服务通知。MIUI/HyperOS 可能还需要允许“后台弹出界面”。悬浮窗会遮挡目标界面；DSH 执行操作时建议收起为悬浮球。Bridge 会过滤自身悬浮窗，避免 `android_state` 把悬浮窗当成目标界面。

## 目录结构

```text
app/                    Android 无障碍桥源码
dsh-plugin/             Cordis 插件（android-tool、android-compat）
compat/                 flock / require-builtin Android 兼容层
deploy/                 Termux setup 与临时 HTTP 服务
scripts/                termux-exec.sh、start-dsh-root.sh、start-dsh.sh
tests/                  Android 工具回归测试
install.ps1             PC 一键部署（root）
install.sh              Android shell root 一键部署
install-noroot.sh       Termux 无 root 安装/启动
gradle/ gradlew*        Gradle Wrapper
dist/                   本地构建/发布产物（git 忽略）
LICENSE                 GNU GPL v3
```

## 环境要求

- Android 11+，arm64
- Termux（F-Droid 版本）
- root 可选（Magisk / KernelSU 等）；Shizuku 可选（no-root 下提供 shell 权限/虚拟屏启动）：
  - root 模式：自动安装 Bridge、启用无障碍、`android_shell`、防冻结常驻
  - 无 root 模式：手动安装 Bridge APK、手动启用无障碍；安装 Shizuku 后可用 shell 权限和虚拟屏应用启动
- PC 端一键部署需要：adb、Node.js；构建 APK 还需要 JDK 和 Android SDK
- 可选：Chrome（`install.ps1` 自动打开 Web UI 时使用）

## 快速开始

### 方式一：设备端安装包（release 里的 `dsh-android.tar.gz`）

设备需要先安装 Termux。若没有，也可以把 Termux APK 推到：

```text
/data/local/tmp/com.termux.apk
```

`install.sh` 会自动检测。

```powershell
adb push .\dsh-android.tar.gz /data/local/tmp/
adb shell "su -c 'tar -xzf /data/local/tmp/dsh-android.tar.gz -C /data/local/tmp && sh /data/local/tmp/dsh-android/install.sh -HOST 127.0.0.1'"
```

`-HOST` 是 dsh Web UI 的监听地址：

- 只用设备本机访问：`-HOST 127.0.0.1`（默认）
- 从 PC / VPN 访问：填设备在局域网或 VPN 中的地址

脚本完成后只输出 URL，不自动打开浏览器：

```text
http://127.0.0.1:3080/?token=...
```

root 安装请把压缩包解压到 `/data/local/tmp`，不要把 APK 放在 `/sdcard` 后直接运行；`pm install` 无法读取 FUSE 路径。

### 方式二：PC 一键部署

```powershell
git clone <repo-url> dsh-android
cd dsh-android
powershell -ExecutionPolicy Bypass -File .\install.ps1 -BindHost 192.168.1.100
```

`install.ps1` 会自动：

1. 安装并启用 Bridge APK
2. 安装/启动 Termux（可自动下载 F-Droid APK）
3. 在 Termux 中执行 Node/dsh/原生依赖安装
4. 启动 dsh
5. 通过无障碍服务驱动 Chrome 地址栏打开 Web UI
6. 检查 Bridge `/health`

如果 `dist/` 和 `app/build/` 里都没有 APK，它会调用 `gradlew.bat assembleDebug` 现场构建。

### 方式三：无 root（Termux）

无 root 模式不执行 `su`，不调用 `pm`/`settings`/`appops`。无 root 的 `android_shell` 和虚拟屏应用启动通过 Shizuku 完成；Bridge APK 需要手动安装并启用无障碍服务。

1. 安装 Bridge APK（PC 用 adb，或手机文件管理器直接点 APK）：

```powershell
adb install -r dist/dsh-bridge.apk
```

2. 在系统设置中启用无障碍：

```text
设置 → 无障碍 → 已安装的应用 → DSH Bridge → 开启
```

3. MIUI/HyperOS 还需要允许“获取应用列表”：

```text
设置 → 应用 → DSH Bridge → 其他权限 → 获取应用列表
```

如果 adb 可用，也可以尝试（不需要 root）：

```powershell
adb shell appops set ai.deepseek.dshbridge 10022 allow
```

4. 安装并启动 Shizuku：

```text
安装 Shizuku（moe.shizuku.privileged.api）
在 Shizuku 里选择 root 或 ADB 方式启动服务
解锁主屏后，让 DSH Bridge 在 Shizuku 弹窗中获得权限
```

Bridge 提供 `/shizuku` 状态/授权接口；第一次授权可以在 Shizuku 应用内点允许。没有 Shizuku 时，no-root 仍可用无障碍工具，但不能用 `android_shell` 和虚拟屏启动应用。

5. 把安装包推到设备，在 Termux 中运行：

```powershell
adb push dsh-android.tar.gz /sdcard/Download/
```

```bash
termux-setup-storage
cd ~/storage/downloads
tar -xzf dsh-android.tar.gz
cd dsh-android
bash install-noroot.sh -HOST 127.0.0.1
```

需要从 PC/VPN 访问时，把 `-HOST` 改成设备的局域网或 VPN 地址，例如：

```bash
bash install-noroot.sh -HOST 192.168.1.100
```

脚本会安装/更新 plugins、compat 和 Termux 依赖，然后以普通 Termux 用户启动 dsh，输出 URL。不会自动打开浏览器。

无 root 相关命令：

```bash
bash install-noroot.sh -start      # 启动（未配置时先安装）
bash install-noroot.sh -stop       # 停止 dsh；无障碍服务保持启用
bash install-noroot.sh -uninstall  # 清理插件/compat/启动脚本；不卸载 Bridge
```

`install-noroot.sh` 必须在 Termux 里用 `bash` 运行，不能在 adb shell/root shell 里执行 `/data/local/tmp/.../install-noroot.sh`；否则 `pkg` 不在 PATH，setup 会失败。

无 root 模式使用独立目录 `~/.dsh-noroot` 作为 dsh home（`DSH_HOME`），插件、compat、profile 和会话都与 root 模式的 `~/.dsh` 隔离，因此两种模式可以共存，但要用不同端口。首次进入无 root 的 Web UI 也需要重新添加 API Key。`-uninstall` 会保留 `~/.dsh-noroot` 中的会话。

如果 root 模式已占用 3080，无 root 模式请加 `-PORT 3090`，或先停止 root dsh。

无 root 模式的 `android_shell` 和虚拟屏应用启动走 Shizuku；`android_act` 的 `launch` / `open_url` 在虚拟屏上也走 Shizuku。没有 Shizuku 时只能使用无障碍工具，无法启动虚拟屏应用。dsh 由 `nohup` + `termux-wake-lock` 运行；建议允许 Termux 后台运行并关闭电池优化。

注意：无 Shizuku 时 Android 11+/MIUI 可能阻止无 root 的后台应用启动；有 Shizuku 时由 Shizuku shell 执行 `am start --display`。坐标点击、输入、滚动、截图等无障碍操作不受影响。

### 常用安装参数

`install.sh`：

```text
-HOST <ip>       dsh 监听地址，默认 127.0.0.1
-PORT <port>     dsh Web 端口，默认 3080
-stop            停止 dsh 和无障碍桥
-uninstall       停止并卸载 Bridge、清理插件/compat/脚本
-start           确保 Bridge 可用并启动 dsh
-h               帮助
```

`-uninstall` 会保留 Termux、Node、dsh 全局包和会话记录。

`install-noroot.sh`（必须在 Termux 中运行，不能 root）：

```text
-HOST <ip>       dsh 监听地址，默认 127.0.0.1
-PORT <port>     dsh Web 端口，默认 3080
-stop            只停止 dsh；不动无障碍服务
-uninstall       清理插件/compat/启动脚本；不卸载 Bridge
-start           启动 dsh；未安装时自动安装
-h               帮助
```

无 root 脚本不会执行 `su`、`pm`、`settings`、`appops`；`android_shell` 和虚拟屏应用启动通过 Shizuku（shell/root 由 Shizuku 服务决定）。

`install.ps1`：

```text
-Serial <serial>         指定 adb 设备
-BindHost <ip>           dsh 监听地址，默认 127.0.0.1
-WebPort <port>          Web 端口，默认 3080
-DeployPort <port>       临时部署服务起始端口，自动寻找空闲端口
-TermuxApk <path>        指定 Termux APK
-SkipTermuxInstall       不允许自动安装 Termux
-SkipSetup               跳过 Termux 安装步骤
-NoStart                 只安装，不启动 dsh
-KeepDeployBundle        保留临时部署目录，便于排查
```

## 安装 dsh npm 插件

不是直接在 profile 目录里执行 `npm install`；要让 dsh 把插件写入对应 profile，需在 Termux 中执行。先确保有 `pnpm`：

```bash
pkg install pnpm
```

```bash
# root 模式（默认 profile 目录 ~/.dsh）
node "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" \
  plugin --profile web add <npm-package>

# no-root 模式（独立 profile 目录 ~/.dsh-noroot）
DSH_HOME="$HOME/.dsh-noroot" \
  node "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" \
  plugin --profile web add <npm-package>
```

安装后重启对应 dsh 进程生效。

## Android 工具

插件注册的模型工具：

| 工具 | 作用 |
|---|---|
| `android_state` | 读取当前无障碍树、前台包名、元素 id/文本/坐标 |
| `android_act` | 点击、长按、setText、滚动、滑动、全局动作、keyevent/enter、启动 App、等待、查找 |
| `android_apps` | 列出可启动应用 |
| `android_screenshot` | 无障碍截图，保存 JPEG；有附件存储时可附加图片；支持 `displayId` |
| `android_virtual_screen` | 创建/调整/停止/查询无头虚拟显示；返回 `displayId` |
| `android_shell` | root 模式走 `su`；no-root 模式走 Shizuku shell；`description` 必填并显示在审批弹窗；`danger-full-access` 下全部放行，`workspace-write` 下每次调用需要 dsh 审批 |
| `android_health` | 检查 Bridge、前台包名、屏幕尺寸 |

无 root 模式注册 7 个工具：`android_shell` 走 Shizuku，`android_virtual_screen` 启动/管理虚拟屏；root 模式走 `su`。`android_act` 的 `launch` 和 `open_url` 会自动改用 Bridge 的 `launch` / `openUrl`。

示例：

```json
{"maxElements": 300}
```

```json
{"op": "click", "text": "WLAN"}
```

```json
{"op": "set_text", "id": 22, "text": "hello"}
```

```json
{"op": "launch", "package": "com.android.settings"}
```

```json
{"op": "wait", "package": "com.android.settings", "timeoutMs": 8000}
```

```json
{"op": "keyevent", "key": "enter", "displayId": 15}
```

```json
{"command": "input keyevent 26", "description": "按电源键锁屏"}
```

使用约定：

- 每次操作前先 `android_state`，操作后重新读取验证
- element id 只对最近一次 state 有效
- 无障碍点击失败时，`android_act` 会尝试坐标点击
- 按键用 `android_act` 的 `keyevent` / `enter`；其他系统命令用 `android_shell`：root 走 `su`，no-root 走 Shizuku

## 虚拟屏幕

`android_virtual_screen`（root 或 Shizuku）会通过 Bridge 创建一个 **headless 公共虚拟显示**。它不会覆盖或镜像物理屏幕，机主继续使用主屏，dsh 用返回的 `displayId` 在虚拟屏上启动和操作应用：

```json
{"op": "start", "width": 1280, "height": 720, "dpi": 160}
```

```json
{"op": "resize", "width": 800, "height": 600, "dpi": 160}
```

返回示例：

```text
displayId=15 1280x720 160dpi name=dsh-virtual
```

之后所有工具都加 `displayId`：

```json
{"op": "launch", "package": "com.android.settings", "displayId": 15}
```

```json
{"displayId": 15, "maxElements": 300}
```

```json
{"displayId": 15, "maxWidth": 720, "quality": 80}
```

```json
{"displayId": 15, "op": "click", "text": "WLAN"}
```

```json
{"displayId": 15, "op": "tap", "x": 640, "y": 360}
```

```json
{"op": "stop"}
```

行为说明：

- Bridge 创建虚拟屏本身不要求 root；应用启动需要 shell/root 权限。root 模式用 `su`，no-root 模式通过 Shizuku 的 `am start`
- 显示为 public + own-content + presentation + touch + destroy-content-on-removal，headless；不会显示在物理屏幕上
- `android_state`、`android_act`、`android_screenshot` 的 `displayId` 默认是 0（物理屏）
- `launch` / `open_url` 在 root 模式用 `am start --display`，no-root 模式通过 Bridge 调用 Shizuku 执行同样的命令
- `resize` 保留原 `displayId` 和应用任务，不重建虚拟屏；调整后应用需要重新布局，建议稍等再截图
- `launch` 默认不会强制重启应用；如果目标应用不在该虚拟屏，会返回 warning，需要重启并拉过去时显式传 `force: true`
- `open_url` 打开后会再次拉起目标应用，避免 Chrome 等应用把任务留在物理屏导致截图全黑；这一步也不执行 `force-stop`
- 虚拟屏没有系统桌面/状态栏；直接 `launch` 目标应用即可
- 首次创建后请使用返回的 `displayId`；id 每次创建可能不同
- `stop` 会释放虚拟屏并销毁上面的应用；不会把任务搬到主屏

注意：

- 物理屏必须处于**解锁且亮屏**状态。锁屏时公共虚拟屏会显示 `ConnectedDisplayKeyguardPresentation`；息屏后虚拟屏没有可操作窗口，应用无法正常前台运行。解锁并亮屏后需要重新 `launch`
- 部分应用不支持 secondary display，可能仍启动在主屏或拒绝启动

验证主屏不受影响：

1. 在虚拟屏启动前记录 ndroid_state {displayId:0} 和 wm size -d 0\n2. 启动虚拟屏、launch 一个应用、执行 tap/click\n3. 再次读取 ndroid_state {displayId:0}；应仍是原来的主屏包名/activity\n4. 执行 wm size -d 0；主屏尺寸不变\n5. ndroid_virtual_screen stop 后再读取 displayId 0；主屏仍不变

Bridge 使用 display-specific window 查询，不会把虚拟屏的焦点窗口当成主屏；虚拟屏退出时带 DESTROY_CONTENT_ON_REMOVAL，不会把虚拟屏任务迁移到主屏。
- 虚拟屏没有系统装饰和音频；需要使用 `android_act` / `android_screenshot` 操作和观察
- 如果启动失败，先用 `android_shell` 的 `am start --display <id> ...` 确认；root 模式这是主要启动方式

## MIUI / HyperOS 获取应用列表

MIUI 的“获取应用列表”权限对应：

```text
MIUIOP(10022) = OP_GET_INSTALLED_APPS
```

root 安装脚本会自动执行：

```powershell
adb shell appops set ai.deepseek.dshbridge 10022 allow
```

无 root 模式不会执行 appops；请在应用权限页打开“其他权限 → 获取应用列表”，或在 PC 上手动执行上面的 adb 命令。否则 `android_apps` 只能看到 DSH Bridge 自己。

## 构建与打包

构建 APK：

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

Linux/macOS：

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 不提交；用 `ANDROID_HOME` 或 Android Studio 指定 SDK。

生成设备端安装包（PowerShell，维护者用）：

```powershell
$repo = (Get-Location).Path
$out = Join-Path $repo 'dist/dsh-android'
Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out/dist","$out/dsh-plugin","$out/compat","$out/deploy","$out/scripts" | Out-Null

Copy-Item "$repo\install.sh" $out
Copy-Item "$repo\install-noroot.sh" $out
Copy-Item "$repo\app\build\outputs\apk\debug\app-debug.apk" "$out\dist\dsh-bridge.apk"
Copy-Item "$repo\dsh-plugin\*" "$out\dsh-plugin"
Copy-Item "$repo\compat\*" "$out\compat"
Copy-Item "$repo\deploy\setup-termux.sh" "$out\deploy"
Copy-Item "$repo\scripts\*" "$out\scripts"
Copy-Item "$repo\LICENSE" $out
Copy-Item "$repo\README.md" $out

Push-Location "$repo\dist"
try { tar -czf dsh-android.tar.gz dsh-android } finally { Pop-Location }
Get-FileHash "$repo\dist\dsh-android.tar.gz" -Algorithm SHA256
```

`dist/` 被 `.gitignore` 忽略；发布时作为 release asset 上传，不要提交进 git。

## 工作原理

### Bridge HTTP API

Bridge 默认只监听 `127.0.0.1:17654`：

- `/health`
- `/shizuku`（GET 状态，POST request）
- `/shell`（POST，经 Shizuku 执行 shell）
- `/displays`
- `/virtualdisplay`（GET 状态，POST start/stop）
- `/state?maxDepth=28&maxElements=600&displayId=0`
- `/action`（POST JSON）
- `/screenshot?maxWidth=720&quality=80&displayId=0`
- `/apps`
- `/events?since=0`

### Android 兼容层

`deploy/setup-termux.sh` 会：

- 安装 `nodejs-lts`、cmake、clang、make、python
- 安装 `@deepseek-ai/dsh@0.2.1-rc.2`
- 给 koffi 打 Android `statx` 补丁并编译
- 用 node-gyp 编译 node-pty
- 安装 `@img/sharp-wasm32` 和 `@emnapi/runtime`
- 部署 plugins/compat/Cordis patch

`start-dsh-root.sh` 使用：

```sh
node --expose-internals \
  --import "$HOME/.dsh/compat/bootstrap.mjs" \
  "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" --profile web \
  --patch ~/.dsh/android.cordis.yml \
  --host $HOST --port $PORT --no-open
```

无 root 模式使用 `scripts/start-dsh.sh`，以 Termux 用户通过 `nohup` 启动，并设置：

```sh
DSH_HOME="$HOME/.dsh-noroot"
DSH_ANDROID_NO_ROOT=1
```

`DSH_ANDROID_NO_ROOT=1` 会让 `android_act` 的 `launch` / `open_url` 使用 Bridge，并让 `android_shell` 走 Shizuku；root 模式的 `start-dsh-root.sh` 会显式清掉这个变量。

`android-compat` 插件负责 Android 上 `fsync("/")` 返回 `EINVAL` 的兼容，否则 `read_image` 和附件写入会失败。

## root 是否必需

Bridge APK 本身不需要 root。root 主要提升自动化和控制能力：

- 以 root + `setsid` 常驻 dsh，降低 HyperOS 冻结 Termux 的概率
- 安装脚本自动执行 `pm install`、无障碍设置、Termux RunCommand
- `android_shell` 的 `input keyevent`、`screencap`、`am/pm/settings` 等
- root `monkey` 启动应用，绕过后台启动限制

无 root 模式由 `install-noroot.sh` 安装：

- 不执行 `su`，不修改 `pm/settings/appops`
- `android_shell` 通过 Shizuku
- `launch` / `open_url` 走 Bridge/Shizuku，不依赖 `su`
- 无 root 的 dsh 以 Termux 用户运行，仍可能被 OEM 后台策略冻结

无障碍树读取、点击、输入、滚动、截图、应用列表、health 在无 root 下仍可用，前提是 Bridge APK 已安装、无障碍已启用、MIUI 应用列表权限已授予。

## 安全

- `android_shell` 是任意 shell；root 模式为 root，no-root 为 Shizuku shell（Shizuku 以 root 启动时也是 root）。`danger-full-access` 下插件直接放行，不弹审批；`workspace-write` 下插件为每次调用请求用户批准
- 无 root 模式通过 Shizuku 暴露 `android_shell`；dsh 默认使用 `workspace-write`，文件写入受 workspace 限制
- dsh 默认使用 `workspace-write`；需要完全访问时可手动设置 `DSH_PERMISSION_MODE=danger-full-access`，但那会跳过审批
- Bridge 只绑 `127.0.0.1` 但没有鉴权，本机其他 App 也能调用
- Web UI 用 token 认证；不要把带 token 的 URL 发到不可信环境
- 建议只在专用测试设备上运行

当前 `dist` 中的 APK 是 debug 签名。公开 release 建议使用固定 release keystore 重新签名；签名不一致会导致无法覆盖安装。

## 测试

设备上运行：

```powershell
adb push tests/android-tools.mjs /data/local/tmp/dsh-android/tests/
adb shell su -c "cd /data/local/tmp/dsh-android/tests && HOME=/data/data/com.termux/files/home node android-tools.mjs"
```

测试覆盖：

- `android_health`
- `android_apps`
- `android_state`
- `android_screenshot`
- `android_virtual_screen`：start / state / screenshot / status / stop
- `android_shell` (root)
- `android_act`：launch / wait / click / set_text / clear_text / global

需要设备已启用 Bridge、Termux 中已安装依赖。root 模式用 `su` 运行。

无 root 模式在 Termux 中运行：

```bash
cd ~/dsh-android/tests
DSH_ANDROID_NO_ROOT=1 node android-tools.mjs
```

`android_shell` 在无 root 模式下通过 Shizuku 运行；`android_virtual_screen` 也走 Bridge + Shizuku。需要先启动 Shizuku 并授予 DSH Bridge 权限。Android/MIUI 可能阻止后台启动，所以无 root 测试会跳过 launch 后的 `wait` 和 Settings 导航检查。

## 常见问题

**`flock is not supported on android-arm64`**

没有通过 `--import .../bootstrap.mjs` 启动 dsh，或 compat bootstrap 缺失。root 模式使用 `start-dsh-root.sh`，无 root 模式使用 `start-dsh.sh`，不要直接 `node bin.js`。

**`Could not load the "sharp" module`**

Termux 中的 sharp WASM 没装好。重新执行 `deploy/setup-termux.sh`，或确认：

```text
$PREFIX/lib/node_modules/@deepseek-ai/dsh/node_modules/@img/sharp-wasm32
```

**`EINVAL: invalid argument, fsync`**

`android-compat` 插件没有加载，或 Cordis patch 没有包含它。root 模式确认 `~/.dsh/android.cordis.yml`，无 root 模式确认 `~/.dsh-noroot/android.cordis.yml` 同时加载 `android-compat` 和 `android-cua`。

**`workspace-write` 下无法使用 `bash`**

在 Android/Termux 上，dsh 的 `bash` 工具在 `workspace-write` 下不可用，这不是权限规则本身的问题：

- `dsh-sandbox-local` 只登记 `linux`、`darwin`、`win32` 三个平台；Termux 的 Node 报告 `process.platform === "android"`，平台链为空，dsh 直接抛出 `SANDBOX_UNAVAILABLE`。
- Android 内核通常未启用 `CONFIG_USER_NS`，也未必启用 Landlock；`bwrap` 和 Landlock 后端不能假定可用。dsh 是 fail-closed，不会在无沙箱状态下执行 `bash`。
- `read` / `write` / `edit` / `glob` / `grep` 等文件工具仍可用；命令改用 `android_shell`（`workspace-write` 下每次审批），或手动切换到 `danger-full-access`（无 OS 级沙箱）。
- no-root 模式下 `android_shell` 依赖 Shizuku 已运行并授权。

`start-dsh-root.sh` 和 `start-dsh.sh` 不再设置 `DSH_PERMISSION_MODE`，dsh 使用默认 `workspace-write`；只有手动设置 `danger-full-access` 时才会全部放行。

**`android_shell` 现在需要审批**

`android_shell` 会在 `workspace-write` 会话中逐次请求用户批准；`description` 必填，审批弹窗会显示用途和命令。拒绝、取消或无审批通道时不会执行。`danger-full-access` 会话直接放行，不弹审批。

**`glob` / `grep` 不可用 / 缺少 `@vscode/ripgrep-android-arm64`**

安装脚本现在会安装 Termux `ripgrep` 并生成 `@vscode/ripgrep-android-arm64` 平台包。升级 dsh 后重新执行安装脚本或 `bash scripts/ensure-ripgrep.sh`，然后重启 dsh（旧进程会缓存失败路径）。

**`android_apps` 只有一个应用**

MIUI/HyperOS 未允许“获取应用列表”。root 安装器会自动设置；无 root 或手动安装时请执行（或到应用权限页开启）：

```powershell
adb shell appops set ai.deepseek.dshbridge 10022 allow
```

**无 root 模式的 `android_shell` / 虚拟屏应用启动**

无 root 模式通过 Shizuku 执行 `android_shell` 和虚拟屏的 `am start --display`。确认 Shizuku 服务正在运行、DSH Bridge 已授权，并且主屏亮屏解锁；否则 Bridge 会返回 Shizuku 未运行/未授权。

**虚拟屏显示锁屏 / 应用不能在虚拟屏启动**

公共虚拟屏会跟随主屏的 keyguard。先解锁并保持亮屏，再调用 `android_virtual_screen` 或重新 `launch`；息屏后虚拟屏没有可操作窗口。如果公司/OEM 策略仍然阻止，请在 root 模式用 `am start --display <id> ...`。no-root 模式下 Bridge 通过 Shizuku 的 shell 权限启动应用；没有 Shizuku 或未授权时会被 Android 权限拒绝。

**虚拟屏启动应用失败**

虚拟屏没有系统桌面，必须用 `android_act` 的 `launch`/`open_url` 指定 `displayId`。部分应用只支持主屏；可以换应用，或使用 `android_shell` 的 `am start --display` 并检查 `dumpsys activity`。

**Bridge 17654 不通**

检查无障碍服务是否启用；APK 是否安装。root 模式可重新执行 `install.sh`；无 root 模式重新安装 APK 并在系统设置中启用无障碍。MIUI 上可尝试 force-stop 后重新启动 Bridge。

**Termux 被冻结 / dsh 停止运行**

root 模式用 `setsid` 常驻；无 root 模式用 `nohup` + `termux-wake-lock`，Termux 仍可能被 OEM 电池策略冻结，需要允许后台运行并关闭电池优化。

**端口 3080 被占用**

```powershell
adb shell su -c "sh /data/local/tmp/dsh-android/install.sh -stop"
```

无 root 模式在 Termux 中执行 `bash install-noroot.sh -stop`。或使用 `-PORT` 换端口。

**Token 失效**

dsh 重启或插件重载会轮换 token。查看：

```text
~/dsh-root.log   # root 模式
~/dsh.log        # 无 root 模式
```

最后一行 `dsh web:` 是当前 URL。

**没有自动打开浏览器**

设备端 `install.sh` 和 `install-noroot.sh` 都只输出 URL，这是预期行为。PC 端 `install.ps1` 会尝试驱动 Chrome 打开。

## 已知限制

- 当前主要验证 Android 11+ arm64 / MIUI HyperOS；其他 OEM 的无障碍和后台限制可能不同
- `android_apps` 的速度取决于应用数量，200+ 应用时约需数秒
- 截图对 `FLAG_SECURE` 窗口无效
- `read_image` / 截图进入模型需要模型支持图片输入
- dsh 版本固定在 `0.2.1-rc.2`，Node 24；升级 dsh 可能需要重新适配 koffi/node-pty/sharp
- Android/Termux 的 `workspace-write` 下 `bash` 不可用：dsh 没有 Android 平台沙箱后端，且 Android 内核通常缺少 bwrap/Landlock 所需支持；使用 `android_shell` 或切换 `danger-full-access`
- 无 root 模式不提供 root shell；`android_shell` 走 Shizuku，`launch` / `open_url` 依赖 Bridge/Shizuku
- root 模式 `android_shell` 会把 `su` 的 stdin/stdout/stderr 重定向到临时文件；手动执行 `su -c "am ..."` 若遇到 `Failed transaction` 或 avc denial，也需要加 `</dev/null >/dev/null 2>&1`
- 无 root 模式不会自动安装 APK、启用无障碍或修改 appops；这些需要手动完成
- 虚拟屏启动应用必须使用 Shizuku/root；no-root 无 Shizuku 时 Bridge 启动其他应用会被 Android 权限拒绝
- 虚拟屏是 public display；主屏锁屏时虚拟屏也会显示 keyguard，需先解锁；息屏后没有可操作窗口
- 虚拟屏没有系统桌面/状态栏，应用需要显式 `launch`；不支持 secondary display 的应用可能启动失败
- 无 root 模式的 dsh 以 Termux 用户运行，可能被 OEM 后台策略冻结
- 设备端 `install.sh` 和 `install-noroot.sh` 都不包含 Termux APK，需要设备已有 Termux 或手动提供 APK
- PC 端自动打开 Web UI 依赖 Chrome 地址栏；没有 Chrome 时只打印 URL
- Bridge HTTP 无鉴权，仅建议绑定 localhost / VPN

## License

GNU General Public License v3.0，见 [LICENSE](LICENSE)。

dsh 上游项目使用 MIT License；Shizuku 使用 Apache-2.0/MIT（Shizuku API）；Termux、Gradle Wrapper 等第三方组件遵循各自许可证。发布二进制时请同时提供本仓库源码链接。

# DSH Android

在 Android 设备上原生运行 DeepSeek Harness（dsh），通过无障碍服务读取屏幕并执行点击、输入、滑动、截图和系统级命令。

项目由三部分组成：

- **DSH Bridge APK**：Android 无障碍服务，监听 `127.0.0.1:17654`
- **dsh 插件**：注册 `android_state`、`android_act`、`android_apps`、`android_screenshot`、`android_health`；root 模式额外注册 `android_shell`
- **安装/兼容层**：在 Termux 中安装 Node、dsh 和原生依赖，支持 root 或普通 Termux 用户运行

> Developer preview。dsh、Termux、Android 版本变化都可能导致脚本需要调整。

## 功能

- 读取当前无障碍树：文本、contentDescription、resource id、bounds、可点击/可编辑/可滚动状态
- 点击、长按、setText、滚动、滑动、全局返回/主页/最近任务
- 通过无障碍服务启动应用、打开 URL；root 模式也可用 `monkey`/`am`
- 无障碍截图，保存为 JPEG；附件存储可用时可附加到模型请求
- root 模式额外提供 `android_shell`：`input keyevent`、`screencap`、`am`、`pm`、`settings`、`svc`、`dumpsys` 等
- 安装脚本自动完成 Termux、Node、dsh、koffi、node-pty、sharp WASM 和插件部署
- 无 root 模式只安装 Termux 侧运行时；不安装 root shell、su、pm/settings/appops 等 root 专属能力

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
- root 可选（Magisk / KernelSU 等）：
  - root 模式：自动安装 Bridge、启用无障碍、`android_shell`、防冻结常驻
  - 无 root 模式：手动安装 Bridge APK、手动启用无障碍；不安装 shell 等 root 能力
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
adb push dsh-android.tar.gz /data/local/tmp/
adb shell su -c "cd /data/local/tmp && tar -xzf dsh-android.tar.gz && sh dsh-android/install.sh -HOST 10.0.0.100"
```

`-HOST` 是 dsh Web UI 的监听地址：

- 只用设备浏览器访问：`-HOST 127.0.0.1`（默认）
- 从 PC / VPN 访问：填设备在局域网或 VPN 中的地址

脚本完成后只输出 URL，不自动打开浏览器：

```text
http://10.0.0.100:3080/?token=...
```

在浏览器打开该 URL。首次进入需要在 Web UI 里添加 DeepSeek API Key 或登录账号。

### 方式二：PC 一键部署

```powershell
git clone <repo-url> dsh-android
cd dsh-android
powershell -ExecutionPolicy Bypass -File .\install.ps1 -BindHost 10.0.0.100
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

无 root 模式不执行 `su`，不调用 `pm`/`settings`/`appops`，也不注册 `android_shell`。Bridge APK 需要手动安装并启用无障碍服务。

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

4. 把安装包推到设备，在 Termux 中运行：

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
bash install-noroot.sh -HOST 10.0.0.100
```

脚本会安装/更新 plugins、compat 和 Termux 依赖，然后以普通 Termux 用户启动 dsh，输出 URL。不会自动打开浏览器。

无 root 相关命令：

```bash
bash install-noroot.sh -start      # 启动（未配置时先安装）
bash install-noroot.sh -stop       # 停止 dsh；无障碍服务保持启用
bash install-noroot.sh -uninstall  # 清理插件/compat/启动脚本；不卸载 Bridge
```

无 root 模式使用独立目录 `~/.dsh-noroot` 作为 dsh home（`DSH_HOME`），插件、compat、profile 和会话都与 root 模式的 `~/.dsh` 隔离，因此两种模式可以共存，但要用不同端口。首次进入无 root 的 Web UI 也需要重新添加 API Key。`-uninstall` 会保留 `~/.dsh-noroot` 中的会话。

如果 root 模式已占用 3080，无 root 模式请加 `-PORT 3090`，或先停止 root dsh。

无 root 模式没有 `android_shell`，`android_act` 的 `launch` / `open_url` 会走无障碍 Bridge，而不是 `su monkey` / `su am`。dsh 由 `nohup` + `termux-wake-lock` 运行；建议允许 Termux 后台运行并关闭电池优化。

注意：Android 11+/MIUI 可能阻止无 root 的后台应用启动。`launch` / `open_url` 可能返回 `launched`，但前台仍停留在 Termux；此时先手动打开目标应用，或用 root 模式。坐标点击、输入、滚动、截图等无障碍操作不受影响。

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

无 root 脚本不会执行 `su`、`pm`、`settings`、`appops`，也不会安装 `android_shell`。

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

## Android 工具

插件注册的模型工具：

| 工具 | 作用 |
|---|---|
| `android_state` | 读取当前无障碍树、前台包名、元素 id/文本/坐标 |
| `android_act` | 点击、长按、setText、滚动、滑动、全局动作、启动 App、等待、查找 |
| `android_apps` | 列出可启动应用 |
| `android_screenshot` | 无障碍截图，保存 JPEG；有附件存储时可附加图片 |
| `android_shell` | root shell；无 root 模式不注册 |
| `android_health` | 检查 Bridge、前台包名、屏幕尺寸 |

无 root 模式只注册 5 个工具（不含 `android_shell`）。`android_act` 的 `launch` 和 `open_url` 会自动改用 Bridge 的 `launch` / `openUrl`。

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
{"command": "input keyevent 26"}
```

使用约定：

- 每次操作前先 `android_state`，操作后重新读取验证
- element id 只对最近一次 state 有效
- 无障碍点击失败时，`android_act` 会尝试坐标点击
- 无障碍做不到的按键、系统命令在 root 模式用 `android_shell`；无 root 模式没有这个工具

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
- `/state?maxDepth=28&maxElements=600`
- `/action`（POST JSON）
- `/screenshot?maxWidth=720&quality=80`
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
DSH_PERMISSION_MODE=danger-full-access
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
DSH_PERMISSION_MODE=danger-full-access
```

`DSH_ANDROID_NO_ROOT=1` 会让插件跳过 `android_shell`，并让 `android_act` 的 `launch` / `open_url` 使用 Bridge。root 模式的 `start-dsh-root.sh` 会显式清掉这个变量。

`android-compat` 插件负责 Android 上 `fsync("/")` 返回 `EINVAL` 的兼容，否则 `read_image` 和附件写入会失败。

## root 是否必需

Bridge APK 本身不需要 root。root 主要提升自动化和控制能力：

- 以 root + `setsid` 常驻 dsh，降低 HyperOS 冻结 Termux 的概率
- 安装脚本自动执行 `pm install`、无障碍设置、Termux RunCommand
- `android_shell` 的 `input keyevent`、`screencap`、`am/pm/settings` 等
- root `monkey` 启动应用，绕过后台启动限制

无 root 模式由 `install-noroot.sh` 安装：

- 不执行 `su`，不修改 `pm/settings/appops`
- 不注册 `android_shell`
- `launch` / `open_url` 走 Bridge，不依赖 `su`
- 无 root 的 dsh 以 Termux 用户运行，仍可能被 OEM 后台策略冻结

无障碍树读取、点击、输入、滚动、截图、应用列表、health 在无 root 下仍可用，前提是 Bridge APK 已安装、无障碍已启用、MIUI 应用列表权限已授予。

## 安全

- `android_shell` 是任意 root shell，等于把整台设备交给模型
- 无 root 模式不注册 `android_shell`，但 dsh 仍使用 `danger-full-access`，仍可读写 Termux 用户能访问的文件
- dsh 默认使用 `danger-full-access`，没有文件沙箱
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
- `android_shell`
- `android_act`：launch / wait / click / set_text / clear_text / global

需要设备已启用 Bridge、Termux 中已安装依赖。root 模式用 `su` 运行。

无 root 模式在 Termux 中运行：

```bash
cd ~/dsh-android/tests
DSH_ANDROID_NO_ROOT=1 node android-tools.mjs
```

`android_shell` 测试会被跳过；`android_act launch` 使用 Bridge，不依赖 root。Android/MIUI 可能阻止后台启动，所以无 root 测试会跳过 launch 后的 `wait` 和 Settings 导航检查。

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

**`sandbox mode "workspace-write"` 不可用**

`start-dsh-root.sh` 和 `start-dsh.sh` 都已设置 `DSH_PERMISSION_MODE=danger-full-access`。如果手动启动，需要自己设置。

**`android_apps` 只有一个应用**

MIUI/HyperOS 未允许“获取应用列表”。root 安装器会自动设置；无 root 或手动安装时请执行（或到应用权限页开启）：

```powershell
adb shell appops set ai.deepseek.dshbridge 10022 allow
```

**无 root 模式没有 `android_shell`**

这是预期行为。无 root 安装器不注册 `android_shell`；请使用 `android_act` 的点击、输入、滚动等无障碍操作。需要 `input keyevent` 或任意 shell 时，必须使用 root 安装。

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
- 无 root 模式不提供 `android_shell`、按键注入和 root `monkey`；`launch` / `open_url` 依赖 Bridge
- 无 root 模式不会自动安装 APK、启用无障碍或修改 appops；这些需要手动完成
- 无 root 模式的 dsh 以 Termux 用户运行，可能被 OEM 后台策略冻结
- 设备端 `install.sh` 和 `install-noroot.sh` 都不包含 Termux APK，需要设备已有 Termux 或手动提供 APK
- PC 端自动打开 Web UI 依赖 Chrome 地址栏；没有 Chrome 时只打印 URL
- Bridge HTTP 无鉴权，仅建议绑定 localhost / VPN

## License

GNU General Public License v3.0，见 [LICENSE](LICENSE)。

dsh 上游项目使用 MIT License；Termux、Gradle Wrapper 等第三方组件遵循各自许可证。发布二进制时请同时提供本仓库源码链接。

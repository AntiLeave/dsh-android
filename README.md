# DSH Android

在 Android 设备上原生运行 DeepSeek Harness（dsh），通过无障碍服务读取屏幕并执行点击、输入、滑动、截图和系统级命令。

项目由三部分组成：

- **DSH Bridge APK**：Android 无障碍服务，监听 `127.0.0.1:17654`
- **dsh 插件**：注册 `android_state`、`android_act`、`android_apps`、`android_screenshot`、`android_shell`、`android_health`
- **安装/兼容层**：在 Termux 中安装 Node、dsh 和原生依赖，并以 root 常驻运行 dsh

> Developer preview。dsh、Termux、Android 版本变化都可能导致脚本需要调整。

## 功能

- 读取当前无障碍树：文本、contentDescription、resource id、bounds、可点击/可编辑/可滚动状态
- 点击、长按、setText、滚动、滑动、全局返回/主页/最近任务
- 通过 root `monkey`/`am` 启动应用、打开 URL
- 无障碍截图，保存为 JPEG；附件存储可用时可附加到模型请求
- root shell：`input keyevent`、`screencap`、`am`、`pm`、`settings`、`svc`、`dumpsys` 等
- 安装脚本自动完成 Termux、Node、dsh、koffi、node-pty、sharp WASM 和插件部署

## 架构

```text
┌──────────────────────────────────────────────┐
│ Android 设备                                  │
│                                              │
│  DSH Bridge APK (普通 App)                   │
│  AccessibilityService + HTTP 127.0.0.1:17654 │
│         ▲                                    │
│         │ JSON/HTTP                          │
│  dsh (Node 24, root 常驻)                    │
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
scripts/                termux-exec.sh、start-dsh-root.sh
tests/                  Android 工具回归测试
install.ps1             PC 一键部署
install.sh              Android shell root 一键部署
gradle/ gradlew*        Gradle Wrapper
dist/                   本地构建/发布产物（git 忽略）
LICENSE                 GNU GPL v3
```

## 环境要求

- Android 11+，arm64
- 已 root（Magisk / KernelSU 等）
- Termux（F-Droid 版本）
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
| `android_shell` | root shell |
| `android_health` | 检查 Bridge、前台包名、屏幕尺寸 |

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
- 无障碍做不到的按键、截图、系统命令用 `android_shell`

## MIUI / HyperOS 获取应用列表

MIUI 的“获取应用列表”权限对应：

```text
MIUIOP(10022) = OP_GET_INSTALLED_APPS
```

安装脚本会执行：

```powershell
adb shell appops set ai.deepseek.dshbridge 10022 allow
```

如果手动安装 APK，可在应用权限页打开“其他权限 → 获取应用列表”，或执行上面的 appops 命令。否则 `android_apps` 只能看到 DSH Bridge 自己。

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

`android-compat` 插件负责 Android 上 `fsync("/")` 返回 `EINVAL` 的兼容，否则 `read_image` 和附件写入会失败。

## 为什么需要 root

Bridge APK 本身不需要 root。root 主要用于：

- 以 root + `setsid` 常驻 dsh，避免 HyperOS 冻结 Termux
- 安装脚本自动执行 `pm install`、无障碍设置、Termux RunCommand
- `android_shell` 的 `input keyevent`、`screencap`、`am/pm/settings` 等
- root `monkey` 启动应用，绕过后台启动限制

没有 root 时，无障碍 UI 操作仍可用，但会失去 shell、按键注入和部分启动/截图能力，并且 dsh 容易被系统冻结。

## 安全

- `android_shell` 是任意 root shell，等于把整台设备交给模型
- dsh 默认使用 `danger-full-access`，没有文件沙箱
- Bridge 只绑 `127.0.0.1` 但没有鉴权，本机其他 App 也能调用
- Web UI 用 token 认证；不要把带 token 的 URL 发到不可信环境
- 建议只在专用测试设备上运行

当前 `dist` 中的 APK 是 debug 签名。公开 release 建议使用固定 release keystore 重新签名；签名不一致会导致无法覆盖安装。

## 测试

设备上运行：

```powershell
adb push tests/android-tools.mjs /data/local/tmp/dsh-android/tests/
adb shell su -c "cd /data/local/tmp/dsh-android/tests && node android-tools.mjs"
```

测试覆盖：

- `android_health`
- `android_apps`
- `android_state`
- `android_screenshot`
- `android_shell`
- `android_act`：launch / wait / click / set_text / clear_text / global

需要设备已启用 Bridge、Termux 中已安装依赖，并以 root 运行。

## 常见问题

**`flock is not supported on android-arm64`**

没有通过 `--import .../bootstrap.mjs` 启动 dsh，或 compat bootstrap 缺失。使用 `start-dsh-root.sh`，不要直接 `node bin.js`。

**`Could not load the "sharp" module`**

Termux 中的 sharp WASM 没装好。重新执行 `deploy/setup-termux.sh`，或确认：

```text
$PREFIX/lib/node_modules/@deepseek-ai/dsh/node_modules/@img/sharp-wasm32
```

**`EINVAL: invalid argument, fsync`**

`android-compat` 插件没有加载，或 Cordis patch 没有包含它。确认 `~/.dsh/android.cordis.yml` 同时加载 `android-compat` 和 `android-cua`。

**`sandbox mode "workspace-write"` 不可用**

`start-dsh-root.sh` 已设置 `DSH_PERMISSION_MODE=danger-full-access`。如果手动启动，需要自己设置。

**`android_apps` 只有一个应用**

MIUI/HyperOS 未允许“获取应用列表”。执行：

```powershell
adb shell appops set ai.deepseek.dshbridge 10022 allow
```

**Bridge 17654 不通**

检查无障碍服务是否启用；APK 是否安装；必要时重新执行 `install.sh`。MIUI 上可尝试 force-stop 后重新启动 Bridge。

**Termux 被冻结 / dsh 停止运行**

当前方案用 root + `setsid` 启动 dsh。Termux 本身不需要保持前台。若使用非 root 方式，需要自行处理电池优化和后台冻结。

**端口 3080 被占用**

```powershell
adb shell su -c "sh /data/local/tmp/dsh-android/install.sh -stop"
```

或使用 `-PORT` 换端口。

**Token 失效**

dsh 重启或插件重载会轮换 token。查看：

```text
~/dsh-root.log
```

最后一行 `dsh web:` 是当前 URL。

**没有自动打开浏览器**

设备端 `install.sh` 只输出 URL，这是预期行为。PC 端 `install.ps1` 会尝试驱动 Chrome 打开。

## 已知限制

- 当前主要验证 Android 11+ arm64 / MIUI HyperOS；其他 OEM 的无障碍和后台限制可能不同
- `android_apps` 的速度取决于应用数量，200+ 应用时约需数秒
- 截图对 `FLAG_SECURE` 窗口无效
- `read_image` / 截图进入模型需要模型支持图片输入
- dsh 版本固定在 `0.2.1-rc.2`，Node 24；升级 dsh 可能需要重新适配 koffi/node-pty/sharp
- 设备端 `install.sh` 不包含 Termux APK，需要设备已有 Termux 或手动提供 APK
- PC 端自动打开 Web UI 依赖 Chrome 地址栏；没有 Chrome 时只打印 URL
- Bridge HTTP 无鉴权，仅建议绑定 localhost / VPN

## License

GNU General Public License v3.0，见 [LICENSE](LICENSE)。

dsh 上游项目使用 MIT License；Termux、Gradle Wrapper 等第三方组件遵循各自许可证。发布二进制时请同时提供本仓库源码链接。

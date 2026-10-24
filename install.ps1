param(
  [string]$Serial = "",
  [string]$BindHost = "127.0.0.1",
  [int]$WebPort = 3080,
  [int]$DeployPort = 18080,
  [string]$TermuxApk = "",
  [switch]$SkipTermuxInstall,
  [switch]$SkipSetup,
  [switch]$NoStart,
  [switch]$KeepDeployBundle
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$BridgeApk = Join-Path $Root "dist/dsh-bridge.apk"
$BuiltApk = Join-Path $Root "app/build/outputs/apk/debug/app-debug.apk"
$TermuxActivity = "com.termux/.app.TermuxActivity"
$BridgeService = "ai.deepseek.dshbridge/ai.deepseek.dshbridge.BridgeService"
$DeployDir = Join-Path $env:TEMP ("dsh-deploy-" + [guid]::NewGuid().ToString("N"))
$ServerProcess = $null
$ReversePort = $false

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ArgList)
  $ErrorActionPreference = 'Continue'
  $out = if ($Serial) { & adb -s $Serial @ArgList 2>&1 } else { & adb @ArgList 2>&1 }
  if ($LASTEXITCODE -ne 0) { throw "adb failed: adb $($ArgList -join ' ')" }
  return $out
}

function Get-AdbText {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ArgList)
  $ErrorActionPreference = 'Continue'
  $out = if ($Serial) { & adb -s $Serial @ArgList 2>&1 } else { & adb @ArgList 2>&1 }
  return (($out | Out-String).Trim())
}

function Invoke-TermuxRoot {
  param([string]$Command)
  $ErrorActionPreference = 'Continue'
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
  $line = "su -c `"sh /data/local/tmp/termux-exec.sh $b64`""
  $out = if ($Serial) { & adb -s $Serial shell $line 2>&1 } else { & adb shell $line 2>&1 }
  return (($out | Out-String).Trim())
}

function Find-FreePort {
  param([int]$Start)
  for ($port = $Start; $port -lt $Start + 50; $port++) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try {
      $listener.Start()
      $listener.Stop()
      return $port
    } catch {
      $listener.Stop()
    }
  }
  throw "No free loopback port in range $Start..$($Start + 49)."
}

function Send-KeyText {
  param([string]$Text)
  Invoke-Adb shell input text ($Text -replace ' ', '%s') | Out-Null
}

function Send-Enter {
  Invoke-Adb shell input keyevent 66 | Out-Null
}

function Confirm-Root {
  $id = Get-AdbText shell su -c id
  if ($id -notmatch "uid=0") {
    throw "No root available (su -c id did not return uid=0). Magisk/root is required."
  }
  Write-Host "[ok] root: $id"
}

function Install-Bridge {
  if (-not (Test-Path $BridgeApk) -and -not (Test-Path $BuiltApk)) {
    $gradlew = Join-Path $Root "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
      throw "Bridge APK is missing and gradlew.bat was not found."
    }
    Write-Host "[build] building the bridge APK"
    Push-Location $Root
    try {
      & $gradlew --no-daemon assembleDebug
      if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug failed" }
    } finally {
      Pop-Location
    }
  }
  if (-not (Test-Path $BridgeApk)) {
    if (Test-Path $BuiltApk) {
      $script:BridgeApk = $BuiltApk
    } else {
      throw "Bridge APK not found after build."
    }
  }
  Write-Host "[1/6] Installing Android accessibility bridge"
  Invoke-Adb install -r $BridgeApk | Out-Null
  Invoke-Adb shell am start -n ai.deepseek.dshbridge/.MainActivity | Out-Null
  $existing = Get-AdbText shell settings get secure enabled_accessibility_services
  if ($existing -eq "null") { $existing = "" }
  if ($existing -notlike "*$BridgeService*") {
    $services = if ($existing.Length -gt 0) { "$existing`:$BridgeService" } else { $BridgeService }
    Invoke-Adb shell settings put secure enabled_accessibility_services $services | Out-Null
  }
  Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null
  try { Invoke-Adb shell appops set ai.deepseek.dshbridge 10022 allow | Out-Null } catch { }
  Write-Host "[ok] accessibility service enabled"
}

function Ensure-Termux {
  $path = Get-AdbText shell pm path com.termux
  if ($path -notmatch "package:") {
    if ($SkipTermuxInstall) { throw "Termux is not installed and -SkipTermuxInstall was used." }
    Write-Host "[2/6] Installing Termux"
    $apk = $TermuxApk
    if (-not $apk) {
      $apk = Join-Path $env:TEMP "com.termux_1002.apk"
      if (-not (Test-Path $apk)) {
        Write-Host "      downloading F-Droid Termux..."
        Invoke-WebRequest -UseBasicParsing -Uri "https://f-droid.org/repo/com.termux_1002.apk" -OutFile $apk
      }
    }
    Invoke-Adb install -r $apk | Out-Null
  } else {
    Write-Host "[2/6] Termux already installed"
  }
  Invoke-Adb shell am start -n $TermuxActivity | Out-Null
  $deadline = (Get-Date).AddSeconds(90)
  while ((Get-Date) -lt $deadline) {
    $ok = Invoke-TermuxRoot 'test -x "$PREFIX/bin/bash" && echo READY'
    if ($ok -match "READY") { Write-Host "[ok] Termux userland ready"; return }
    Start-Sleep -Seconds 3
  }
  throw "Termux bootstrap timed out. Open Termux once, wait for the prompt, then rerun."
}

function Prepare-Deploy {
  Write-Host "[3/6] Preparing deployment bundle and temporary HTTP server"
  New-Item -ItemType Directory -Force -Path (Join-Path $DeployDir ".dsh/plugins") | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $DeployDir ".dsh/compat") | Out-Null
  Copy-Item (Join-Path $Root "dsh-plugin/android-tool.mjs") (Join-Path $DeployDir ".dsh/plugins/android-tool.mjs") -Force
  Copy-Item (Join-Path $Root "dsh-plugin/android-compat.mjs") (Join-Path $DeployDir ".dsh/plugins/android-compat.mjs") -Force
  Copy-Item (Join-Path $Root "dsh-plugin/android.cordis.yml") (Join-Path $DeployDir ".dsh/android.cordis.yml") -Force
  Copy-Item (Join-Path $Root "compat/*") (Join-Path $DeployDir ".dsh/compat/") -Force
  Copy-Item (Join-Path $Root "deploy/setup-termux.sh") (Join-Path $DeployDir "setup.sh") -Force
  Push-Location $DeployDir
  try {
    & tar -czf "deploy.tar.gz" ".dsh"
    if ($LASTEXITCODE -ne 0) { throw "tar packaging failed" }
  } finally {
    Pop-Location
  }
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required on the host for the temporary deployment server."
  }
  $script:DeployPort = Find-FreePort $DeployPort
  $ServerProcess = Start-Process -FilePath "node" -ArgumentList @(
    (Join-Path $Root "deploy/serve.mjs"), $DeployDir, "$DeployPort"
  ) -PassThru -WindowStyle Hidden
  Start-Sleep -Seconds 1
  if ($ServerProcess.HasExited) { throw "Temporary HTTP server failed to start (port $DeployPort in use?)" }
  Invoke-Adb reverse "tcp:$DeployPort" "tcp:$DeployPort" | Out-Null
  $ReversePort = $true
  Write-Host "[ok] temporary server: http://127.0.0.1:$DeployPort"
}

function Remove-OldDeploy {
  Invoke-TermuxRoot 'rm -rf "$HOME/.dsh/plugins" "$HOME/.dsh/compat"; rm -f "$HOME/.dsh/android.cordis.yml" "$HOME/.dsh-setup.done" "$HOME/.dsh-setup.failed"' | Out-Null
}

function Invoke-Setup {
  Write-Host "[4/6] Running setup inside Termux"
  Remove-OldDeploy
  Invoke-Adb shell am start -n $TermuxActivity | Out-Null
  Start-Sleep -Seconds 3
  $size = Get-AdbText shell wm size
  if ($size -match '(\d+)x(\d+)') {
    $tapX = [int]([int]$Matches[1] / 2)
    $tapY = [int]([int]$Matches[2] * 0.45)
    Invoke-Adb shell input tap $tapX $tapY | Out-Null
  } else {
    Invoke-Adb shell input tap 540 1000 | Out-Null
  }
  Start-Sleep -Milliseconds 500
  Send-KeyText "curl -fsSL http://127.0.0.1:$DeployPort/setup.sh -o setup.sh"
  Send-Enter
  Start-Sleep -Seconds 2
  Send-KeyText "bash setup.sh http://127.0.0.1:$DeployPort"
  Send-Enter
  Write-Host "      setup started in Termux; waiting (first run can take 10-20 minutes)..."
  $deadline = (Get-Date).AddMinutes(40)
  $lastLog = (Get-Date)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $marker = Invoke-TermuxRoot 'test -f "$HOME/.dsh-setup.done" && echo DONE; test -f "$HOME/.dsh-setup.failed" && echo FAILED'
    if ($marker -match "DONE") {
      Write-Host "[ok] Termux setup complete"
      return
    }
    if ($marker -match "FAILED") {
      $log = Invoke-TermuxRoot 'tail -n 80 "$HOME/dsh-setup.log" 2>/dev/null'
      throw ("Termux setup failed. Log:" + [Environment]::NewLine + $log)
    }
    if (((Get-Date) - $lastLog).TotalSeconds -ge 30) {
      $tail = Invoke-TermuxRoot 'tail -n 6 "$HOME/dsh-setup.log" 2>/dev/null'
      if ($tail) { Write-Host "      $tail" }
      $lastLog = (Get-Date)
    }
  }
  $log = Invoke-TermuxRoot 'tail -n 80 "$HOME/dsh-setup.log" 2>/dev/null'
  throw ("Termux setup timed out or failed. Log:" + [Environment]::NewLine + $log)
}

function Open-WebUi {
  param([string]$TokenUrl, [string]$CleanUrl)
  Invoke-Adb forward tcp:17654 tcp:17654 | Out-Null
  Invoke-Adb shell "am start -a android.intent.action.VIEW -d '$CleanUrl'" | Out-Null
  Start-Sleep -Seconds 2
  try {
    $state = Invoke-RestMethod -Uri "http://127.0.0.1:17654/state?maxElements=300" -TimeoutSec 10
    $bar = $state.nodes | Where-Object { $_.viewId -eq "com.android.chrome:id/url_bar" -and $_.editable } | Select-Object -First 1
    if ($bar) {
      $body = @{ op = "setText"; id = $bar.id; text = $TokenUrl } | ConvertTo-Json -Compress
      Invoke-RestMethod -Uri "http://127.0.0.1:17654/action" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 10 | Out-Null
      Invoke-Adb shell input keyevent 66 | Out-Null
      Start-Sleep -Seconds 4
      $state = Invoke-RestMethod -Uri "http://127.0.0.1:17654/state?maxElements=300" -TimeoutSec 10
      $bar = $state.nodes | Where-Object { $_.viewId -eq "com.android.chrome:id/url_bar" -and $_.editable } | Select-Object -First 1
      if ($bar) {
        $body = @{ op = "setText"; id = $bar.id; text = $CleanUrl } | ConvertTo-Json -Compress
        Invoke-RestMethod -Uri "http://127.0.0.1:17654/action" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 10 | Out-Null
        Invoke-Adb shell input keyevent 66 | Out-Null
      }
      return
    }
  } catch {
    Write-Host "[warn] could not drive Chrome's address bar; falling back to intents"
  }
  Invoke-Adb shell "am start -a android.intent.action.VIEW -d '$TokenUrl'" | Out-Null
  Start-Sleep -Seconds 2
  Invoke-Adb shell "am start -a android.intent.action.VIEW -d '$CleanUrl'" | Out-Null
}

function Start-Dsh {
  Write-Host "[5/6] Starting dsh"
  Invoke-TermuxRoot 'pkill -f "[l]ib/bin.js --profile web" 2>/dev/null || true' | Out-Null
  Start-Sleep -Seconds 2
  Invoke-TermuxRoot ': > "$HOME/dsh-root.log"; rm -f "$HOME/dsh-root.pid"' | Out-Null
  $envLine = "DSH_WEB_HOST=$BindHost DSH_WEB_PORT=$WebPort"
  Invoke-TermuxRoot "$envLine sh /data/local/tmp/start-dsh-root.sh" | Out-Null
  $deadline = (Get-Date).AddSeconds(60)
  $token = ""
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $line = Invoke-TermuxRoot 'grep "dsh web:" "$HOME/dsh-root.log" 2>/dev/null | tail -1'
    if ($line -match "token=([A-Za-z0-9_-]+)") { $token = $Matches[1]; break }
  }
  if (-not $token) {
    $log = Invoke-TermuxRoot 'tail -n 80 "$HOME/dsh-root.log" 2>/dev/null'
    throw ("dsh startup timed out. Log:" + [Environment]::NewLine + $log)
  }
  # The first token can be superseded by an HMR/plugin reload; settle and
  # re-read the latest line before opening the browser.
  Start-Sleep -Seconds 4
  $line = Invoke-TermuxRoot 'grep "dsh web:" "$HOME/dsh-root.log" 2>/dev/null | tail -1'
  if ($line -match "token=([A-Za-z0-9_-]+)") { $token = $Matches[1] }
  $listen = Invoke-TermuxRoot "ss -ltnp 2>/dev/null | grep `"${BindHost}:${WebPort}`""
  if (-not $listen) {
    $log = Invoke-TermuxRoot 'tail -n 80 "$HOME/dsh-root.log" 2>/dev/null'
    throw ("dsh is not listening. Log:" + [Environment]::NewLine + $log)
  }
  $url = "http://${BindHost}:${WebPort}/?token=$token"
  Write-Host "[ok] dsh: $url"
  Open-WebUi -TokenUrl $url -CleanUrl "http://${BindHost}:${WebPort}/"
  return $url
}

function Show-Health {
  Write-Host "[6/6] Checking accessibility bridge"
  Invoke-Adb forward tcp:17654 tcp:17654 | Out-Null
  Start-Sleep -Milliseconds 500
  try {
    $health = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:17654/health" -TimeoutSec 5).Content
    Write-Host "[ok] bridge: $health"
  } catch {
    Write-Host "[warn] bridge is not responding; check the accessibility service."
  }
}

if ($BindHost -in @("0.0.0.0", "::", "*")) {
  throw "BindHost must be a concrete local address; 0.0.0.0 is rejected by dsh."
}

try {
  Write-Host "DSH Android one-shot deployment"
  Write-Host "  device: $(if ($Serial) { $Serial } else { 'default adb device' })"
  Write-Host "  web:    $BindHost`:$WebPort"
  Invoke-Adb devices | Out-Null
  Confirm-Root
  Install-Bridge
  Invoke-Adb push (Join-Path $Root "scripts/termux-exec.sh") "/data/local/tmp/termux-exec.sh" 2>$null | Out-Null
  Invoke-Adb push (Join-Path $Root "scripts/start-dsh-root.sh") "/data/local/tmp/start-dsh-root.sh" 2>$null | Out-Null
  Invoke-Adb shell chmod 666 /data/local/tmp/termux-exec.sh /data/local/tmp/start-dsh-root.sh | Out-Null
  Ensure-Termux
  Prepare-Deploy
  if (-not $SkipSetup) { Invoke-Setup } else { Write-Host "[4/6] skipped Termux setup (-SkipSetup)" }
  if (-not $NoStart) {
    $url = Start-Dsh
    Show-Health
    Write-Host ""
    Write-Host "Done."
    Write-Host "Web UI: $url"
    Write-Host 'Stop dsh:   adb shell su -c ''pkill -f "[l]ib/bin.js --profile web"'''
    Write-Host "Stop bridge: adb shell settings put secure accessibility_enabled 0"
  } else {
    Write-Host "[5/6] skipped dsh start (-NoStart)"
  }
} finally {
  if ($ServerProcess -and -not $ServerProcess.HasExited) {
    Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
  }
  if ($ReversePort) {
    & adb reverse --remove "tcp:$DeployPort" 2>$null | Out-Null
  }
  if (-not $KeepDeployBundle) {
    Remove-Item -LiteralPath $DeployDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}

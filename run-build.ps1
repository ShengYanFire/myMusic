# ============================================================
#  MyMusic 一键构建 / 运行脚本
#  请务必在【你自己的终端或文件管理器】里运行本脚本（不要经由 AI 沙箱，
#  沙箱进程无外网）。你的进程有完整网络，脚本会自动完成：
#    1. 找到 JDK 17/21（优先 JAVA_HOME，其次 DevEco 自带 JDK 17）
#    2. 检查/自动安装 Android SDK 组件（cmdline-tools + Platform 34 + Build-Tools + Platform-Tools）
#    3. 缺失时获取 gradle-wrapper.jar，然后 gradlew 编译 debug APK
#    4. （可选 -Run）安装并启动到已连接的手机/模拟器
#
#  用法（在项目根目录 E:\test\my-music）：
#    powershell -ExecutionPolicy Bypass -File run-build.ps1        # 只构建
#    powershell -ExecutionPolicy Bypass -File run-build.ps1 -Run   # 构建 + 装到设备
#  也可以直接右键 run-build.ps1 → 使用 PowerShell 运行
# ============================================================
param(
    [switch]$Run
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$androidDir = Join-Path $root 'android'

Write-Host '== MyMusic 构建脚本 ==' -ForegroundColor Cyan

# ---------- 1) 找可用的 JDK 17/21/22 ----------
function Test-Jdk([string]$p) {
    $j = Join-Path $p 'bin\java.exe'
    if (-not (Test-Path $j)) { return $false }
    try {
        $v = (& $j -version 2>&1 | Select-Object -First 1) -join ' '
        return ($v -match '"(17|21|22)\.')
    } catch { return $false }
}
$jdk = $null
if ($env:JAVA_HOME -and (Test-Jdk $env:JAVA_HOME)) { $jdk = $env:JAVA_HOME }
elseif (Test-Jdk 'C:\Program Files\Huawei\DevEco Studio\jbr') { $jdk = 'C:\Program Files\Huawei\DevEco Studio\jbr' }
else {
    $cands = @('C:\Program Files\Java','C:\Program Files\Eclipse Adoptium','D:\Program Files\Java','D:\Program Files\Eclipse Adoptium')
    foreach ($c in $cands) {
        if (Test-Path $c) {
            $hit = Get-ChildItem $c -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Jdk $_.FullName } | Select-Object -First 1
            if ($hit) { $jdk = $hit.FullName; break }
        }
    }
}
if (-not $jdk) {
    Write-Host '[错误] 未找到 JDK 17/21。可：安装任一 JDK 17/21 并设 JAVA_HOME，或用 Android Studio 的 Gradle JDK 下载。' -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
Write-Host "[1/3] JDK = $jdk"

# ---------- 2) Android SDK ----------
$sdk = 'D:\Android\Sdk'
$lp = Join-Path $androidDir 'local.properties'
if (Test-Path $lp) {
    $m = Select-String -Path $lp -Pattern '^sdk\.dir=(.+)$'
    if ($m) { $sdk = $m.Matches[0].Groups[1].Value.Trim() }
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "[2/3] SDK = $sdk"

$sdkReady = (Test-Path (Join-Path $sdk 'platforms\android-34')) -and (Test-Path (Join-Path $sdk 'platform-tools'))
if (-not $sdkReady) {
    Write-Host '   SDK 组件缺失，尝试自动下载安装（首次 1-3 分钟，需联网）...'
    try {
        $tools = Join-Path $sdk 'cmdline-tools'
        New-Item -ItemType Directory -Force -Path $tools | Out-Null
        $sm = Join-Path $tools 'latest\bin\sdkmanager.bat'
        if (-not (Test-Path $sm)) {
            $zip = Join-Path $env:TEMP 'cmdline-tools.zip'
            Write-Host '   下载 cmdline-tools...'
            Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
            Expand-Archive -Path $zip -DestinationPath $tools -Force
            Rename-Item (Join-Path $tools 'cmdline-tools') (Join-Path $tools 'latest') -Force
        }
        Write-Host '   接受许可并安装 Platform 34 / Build-Tools / Platform-Tools...'
        $yes = 1..30 | ForEach-Object { 'y' }
        $yes | & $sm --sdk_root="$sdk" --licenses 2>&1 | Out-Null
        $yes | & $sm --sdk_root="$sdk" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | Out-Null
    } catch {
        Write-Host "[提示] SDK 自动安装失败：$($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host '   请改用 Android Studio：打开 android 目录 → 按提示 Install SDK components（Android 14 Platform）。' -ForegroundColor Yellow
    }
}
if (-not (Test-Path (Join-Path $sdk 'platforms\android-34'))) {
    Write-Host '[错误] SDK 仍未就绪（缺 platforms;android-34）。请用 Android Studio 的 SDK Manager 安装 Android 14 Platform 后重试。' -ForegroundColor Red
    exit 1
}
Write-Host '   SDK 就绪。'

# ---------- 3) 确保 gradle-wrapper.jar ----------
$wj = Join-Path $androidDir 'gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path $wj)) {
    Write-Host '   缺少 gradle-wrapper.jar，尝试获取...'
    $g = Get-Command gradle -ErrorAction SilentlyContinue
    if ($g) {
        Push-Location $androidDir
        try { & gradle wrapper --gradle-version 8.9 } finally { Pop-Location }
    } else {
        try {
            Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $wj
        } catch {
            Write-Host '[错误] 无法获取 gradle-wrapper.jar。请用 Android Studio 打开 android 目录让 AS 自动生成后重试。' -ForegroundColor Red
            exit 1
        }
    }
}

# ---------- 4) 构建 ----------
Write-Host '[3/3] gradlew :app:assembleDebug ...'
Push-Location $androidDir
try {
    & .\gradlew.bat :app:assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { Write-Host '[错误] 构建失败，请把上方日志发我。' -ForegroundColor Red; exit $LASTEXITCODE }
} finally { Pop-Location }

$apk = Get-ChildItem (Join-Path $androidDir 'app\build\outputs\apk\debug') -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { Write-Host '[错误] 未找到 APK 产物。' -ForegroundColor Red; exit 1 }
Write-Host "`n构建成功: $($apk.FullName)" -ForegroundColor Green

# ---------- 可选：安装并启动 ----------
if ($Run) {
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $adb) {
        $devices = (& $adb devices) | Select-String -Pattern 'device$'
        if ($devices) {
            & $adb install -r $apk.FullName
            if ($LASTEXITCODE -eq 0) {
                & $adb shell am start -n com.mymusic.player/.ui.MainActivity
                Write-Host '已安装并启动到设备。' -ForegroundColor Green
            }
        } else {
            Write-Host '[提示] 未检测到已连接设备/模拟器，跳过安装。请先连接手机（开 USB 调试）或启动模拟器。' -ForegroundColor Yellow
        }
    }
}

# ============================================================
#  fix-gradle.ps1 — 用本地下载的 Gradle 发行包修复 wrapper（完全离线）
#  适用场景：wrapper 报 "Could not resolve gradle:gradle:8.14.3"
#  （即 Gradle 发行包下载不下来）。
#
#  前提：先用浏览器下载 gradle-8.14.3-bin.zip（约 130MB）存到：
#        D:\gradle-8.14.3-bin.zip
#  可用的镜像地址（浏览器里打开即下载，挑一个能下的）：
#     华为云:  https://mirrors.huaweicloud.com/gradle/gradle-8.14.3-bin.zip
#     清华:    https://mirrors.tuna.tsinghua.edu.cn/gradle/gradle-8.14.3-bin.zip
#     腾讯云:  https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip
#     官方:    https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
#
#  用法（项目根目录）：
#     powershell -ExecutionPolicy Bypass -File fix-gradle.ps1
#     powershell -ExecutionPolicy Bypass -File fix-gradle.ps1 -Zip "D:\别的路径\gradle-8.14.3-bin.zip"
#
#  做完后：重新同步 AS（File → Sync Project with Gradle Files），或直接跑 build.bat。
# ============================================================
param(
    [string]$Zip = 'D:\gradle-8.14.3-bin.zip'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$androidDir = Join-Path $root 'android'

if (-not (Test-Path $Zip)) {
    Write-Host "[错误] 找不到 $Zip" -ForegroundColor Red
    Write-Host '       请先用浏览器从上面的镜像地址下载 gradle-8.14.3-bin.zip。' -ForegroundColor Yellow
    exit 1
}

$jdk = 'C:\Program Files\Huawei\DevEco Studio\jbr'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host '[错误] 未找到 JDK 17（DevEco Studio jbr）。' -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path

# 1) 解压发行包到临时目录
$extract = Join-Path $env:TEMP 'gradle-8.14.3-local'
$gradle = Join-Path $extract 'gradle-8.14.3\bin\gradle.bat'
if (-not (Test-Path $gradle)) {
    Write-Host '解压 Gradle 发行包...'
    Expand-Archive -Path $Zip -DestinationPath $extract -Force
}
if (-not (Test-Path $gradle)) {
    Write-Host "[错误] 解压后未找到 $gradle，发行包可能不完整。" -ForegroundColor Red
    exit 1
}

# 2) 用本地 Gradle 生成 wrapper jar（离线，无需网络）
Push-Location $androidDir
try {
    Write-Host '生成 gradle-wrapper.jar ...'
    & $gradle wrapper --gradle-version 8.14.3 --no-daemon
    if ($LASTEXITCODE -ne 0) { Write-Host '[错误] 生成 wrapper 失败。' -ForegroundColor Red; exit $LASTEXITCODE }
} finally { Pop-Location }

$wj = Join-Path $androidDir 'gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path $wj)) { Write-Host '[错误] gradle-wrapper.jar 未生成。' -ForegroundColor Red; exit 1 }
Write-Host "wrapper jar 已生成: $wj"

# 3) 让 wrapper 指向本地发行包（file://），彻底避免联网下载 Gradle
$props = Join-Path $androidDir 'gradle\wrapper\gradle-wrapper.properties'
$content = Get-Content $props -Raw
$fileUrl = 'file\:/' + ($Zip -replace '\\','/')
$content = [regex]::Replace($content, 'distributionUrl=.*', "distributionUrl=$fileUrl")
Set-Content -Path $props -Value $content -Encoding ASCII
Write-Host "distributionUrl 已指向本地: $fileUrl"

Write-Host ''
Write-Host '完成！接下来：' -ForegroundColor Green
Write-Host '  方式 A（推荐）: Android Studio → File → Sync Project with Gradle Files'
Write-Host '  方式 B: 项目根目录运行 build.bat'
Write-Host '（后续 Maven 依赖仍需联网下载；你这边能访问 Google 仓库，应该没问题。）'

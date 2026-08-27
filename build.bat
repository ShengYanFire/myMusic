@echo off
rem ============================================================
rem  MyMusic 一键构建脚本（Windows）
rem  用法：在项目根目录运行  build.bat
rem  产物：android\app\build\outputs\apk\debug\app-debug.apk
rem ============================================================
setlocal
cd /d "%~dp0android"

rem 1) 确保 Gradle wrapper 存在（二进制 jar 未入库）
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [MyMusic] gradle-wrapper.jar 不存在，尝试生成...
    where gradle >nul 2>nul
    if not errorlevel 1 (
        gradle wrapper --gradle-version 8.9
        if errorlevel 1 (
            echo [MyMusic] wrapper 生成失败
            exit /b 1
        )
    ) else (
        echo [MyMusic] 未安装 Gradle。请用 Android Studio 打开 "android" 目录，
        echo [MyMusic] 等它同步后会自动生成 wrapper；或先安装 Gradle 8.9。
        exit /b 1
    )
)

rem 2) 编译 debug APK
echo [MyMusic] 开始编译 debug APK...
call gradlew.bat :app:assembleDebug %*
if errorlevel 1 (
    echo [MyMusic] 构建失败，请把上方日志发给我。
    exit /b 1
)

rem 3) 输出产物路径
echo.
echo [MyMusic] 构建成功！APK 位置：
for /r "app\build\outputs\apk\debug" %%f in (*.apk) do echo   %%f

endlocal

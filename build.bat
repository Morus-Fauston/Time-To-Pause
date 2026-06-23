@echo off
REM ================================================================
REM 一键构建脚本 — 如果 Gradle 9.6 + AGP 8.9.0 不兼容 Java 25，
REM 可以回退到 Gradle 8.5 + AGP 8.2.2 + 手动安装 Java 21
REM ================================================================

echo.
echo === Time To Pause 构建工具 ===
echo.

:check_java
if "%JAVA_HOME%"=="" (
    echo [WARN] JAVA_HOME 未设置
    echo 请设置: set JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot
    echo 或安装 Java 21  https://adoptium.net/
    goto :end
)

echo JAVA_HOME=%JAVA_HOME%
echo.
echo 开始构建...
cd /d "%~dp0"
call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [OK] 构建成功！
    echo APK 位置: app\build\outputs\apk\debug\
) else (
    echo.
    echo [FAIL] 构建失败
    echo 常见原因：
    echo   1. Java 版本不兼容 → 尝试安装 Java 21
    echo      winget install "Eclipse Temurin JDK with Hotspot 21"
    echo   2. 网络问题 → 手动下载 Gradle 后放到:
    echo      %%USERPROFILE%%\.gradle\wrapper\dists\
)

:end
pause

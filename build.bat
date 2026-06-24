@echo off
REM ================================================================
REM 一键构建脚本 — 如果 Gradle 9.6 + AGP 8.9.0 不兼容 Java 25，
REM 可以回退到 Gradle 8.5 + AGP 8.2.2 + 手动安装 Java 21
REM ================================================================

REM ===================== 配置区域 =====================
REM 修改下面的路径为你的 JDK 安装目录。
REM 如果已设置系统环境变量 JAVA_HOME，脚本会自动使用它。
REM 常见 JDK 安装路径参考:
REM   Microsoft JDK: C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot
REM   Eclipse Temurin: C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot
REM   Oracle JDK:     C:\Program Files\Java\jdk-21
set "DEFAULT_JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"
REM ====================================================

REM 优先使用系统环境变量 JAVA_HOME，未设置则用默认路径
if "%JAVA_HOME%"=="" (
    set "JAVA_HOME=%DEFAULT_JAVA_HOME%"
)

echo.
echo === Time To Pause 构建工具 ===
echo.

:check_java
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [WARN] 未找到 JDK，当前 JAVA_HOME=%JAVA_HOME%
    echo.
    echo 请修改脚本顶部的 DEFAULT_JAVA_HOME 路径
    echo 常见 JDK 安装方式:
    echo   Microsoft JDK 25: winget install "Microsoft.OpenJDK.25"
    echo   Eclipse Temurin:  winget install "Eclipse Temurin JDK with Hotspot 21"
    echo.
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

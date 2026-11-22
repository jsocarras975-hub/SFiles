@rem BoardMind Gradle wrapper launcher (Windows).
@rem Uses a system Gradle if installed; otherwise open this project in
@rem Android Studio, which will download the Gradle distribution named
@rem in gradle\wrapper\gradle-wrapper.properties automatically.
@echo off
where gradle >nul 2>&1
if %ERRORLEVEL% equ 0 (
    gradle %*
    exit /b %ERRORLEVEL%
)
echo Gradle is not installed. Open this project in Android Studio, or install Gradle 8.11.1 and re-run gradlew.bat.
exit /b 1

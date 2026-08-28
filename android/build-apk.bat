@echo off
REM Compila el APK. Ejecutar desde una consola normal de Windows
REM (cmd o PowerShell), no desde Claude Desktop.

setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
cd /d "%~dp0"

echo === Compilando Radio CO ===
call gradlew.bat assembleRelease --console=plain
set RC=%ERRORLEVEL%

echo.
if "%RC%"=="0" (
  echo APK listo en:
  echo   %~dp0app\build\outputs\apk\release\app-release.apk
) else (
  echo La compilacion fallo con codigo %RC%
)
echo %RC% > "%~dp0build-exitcode.txt"
endlocal
exit /b %RC%

@echo off
REM Abre el emulador de Android CON ventana, para poder tocarlo con el raton.
REM Doble clic. Tarda un minuto o dos en arrancar la primera vez.
REM
REM Mientras esta abierto, Claude puede seguir instalando versiones nuevas y
REM sacando capturas por adb: los dos podemos trabajar sobre el mismo telefono.

setlocal
set "SDK=C:\Users\PORCEN038\AppData\Local\Android\Sdk"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

echo Cerrando cualquier emulador anterior...
"%SDK%\platform-tools\adb.exe" emu kill >nul 2>&1
timeout /t 4 /nobreak >nul

echo Arrancando el emulador con ventana...
cd /d "%SDK%\emulator"
start "" emulator.exe -avd Pixel_3_API_35 -no-boot-anim -no-snapshot-save -dns-server 8.8.8.8,1.1.1.1 -netdelay none -netspeed full

echo.
echo Se abrira una ventana con el telefono. Cuando termine de arrancar,
echo Radio CO ya deberia estar instalada (icono de ondas de radio).
echo.
echo Deja esta ventana cerrarse sola.
timeout /t 6 /nobreak >nul
endlocal

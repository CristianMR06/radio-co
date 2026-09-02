@echo off
REM Abre la radio en el navegador del PC. Doble clic y listo.
REM Deja esta ventana abierta mientras escuchas: es el servidor.

cd /d "%~dp0web"
start "" http://localhost:5510
echo.
echo   Radio CO en http://localhost:5510
echo   Cierra esta ventana para apagar el servidor.
echo.
python -m http.server 5510

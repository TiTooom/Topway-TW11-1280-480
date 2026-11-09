@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
rem ------------------------------------------------------------------
rem  Annule l'allegement : reactive tous les paquets de
rem  tools\allegement_liste.txt et remet les animations a 1x.
rem  Un seul paquet : adb shell pm enable <paquet>
rem ------------------------------------------------------------------

set ROOT=%~dp0..
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb
set LISTE=%~dp0allegement_liste.txt
set OUT=%ROOT%\autoradio\allegement
if not exist "%OUT%" mkdir "%OUT%"
set LOG=%OUT%\suivi_allegement.txt
set IP=192.168.0.103

echo.
echo === Annuler l'allegement ===
set /p IP=Adresse IP de l'autoradio [%IP%] :
"%ADB%" connect %IP%:5555
"%ADB%" get-state >nul 2>&1
if errorlevel 1 ( echo Pas de connexion ADB. & pause & exit /b 1 )

echo.>>"%LOG%"
echo ===== ANNULATION %DATE% %TIME% =====>>"%LOG%"
for /f "usebackq eol=# tokens=1 delims=; " %%P in ("%LISTE%") do (
  "%ADB%" shell pm enable %%P
  echo [ALLEGEMENT] reactive %%P>>"%LOG%"
)
"%ADB%" shell settings put global window_animation_scale 1
"%ADB%" shell settings put global transition_animation_scale 1
"%ADB%" shell settings put global animator_duration_scale 1
echo [ALLEGEMENT] animations 1x>>"%LOG%"
echo.
echo Tout est reactive. Redemarrer l'autoradio pour revenir a l'etat d'origine.
pause

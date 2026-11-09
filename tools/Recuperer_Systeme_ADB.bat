@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
rem ------------------------------------------------------------------
rem  Recupere les fichiers systeme de l'autoradio, depuis le PC, par ADB.
rem  Double-clic suffit. Prerequis : debogage USB actif sur l'autoradio
rem  (Sandero : Outils > Systeme et demarrage > Options developpeur),
rem  cable USB relie au PC, ou ADB Wi-Fi (le script demande l'adresse IP).
rem  Les fichiers vont dans autoradio\dumps\SanderoDump_... (ignore par Git : getprop brut,
rem  avec numero de serie et licence OTA). A fusionner ensuite dans autoradio\systeme_*
rem  apres masquage des identifiants (voir le skill topway-image-systeme).
rem  Fonctionne sans root (ce que "shell" peut lire) ; plus complet avec adb root.
rem ------------------------------------------------------------------

set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

echo.
echo === Recuperation des fichiers systeme de l'autoradio ===
"%ADB%" start-server >nul 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo Aucun autoradio en USB.
  set /p IP=Adresse IP de l'autoradio pour ADB Wi-Fi ^(Entree pour abandonner^) :
  if "!IP!"=="" goto :fin
  "%ADB%" connect !IP!:5555
  "%ADB%" get-state >nul 2>&1
  if errorlevel 1 (
    echo Connexion impossible. Verifier le debogage USB, le cable ou le Wi-Fi.
    goto :fin
  )
)
"%ADB%" devices

echo.
echo Tentative adb root ^(sans effet si refuse^)...
"%ADB%" root >nul 2>&1
timeout /t 4 >nul
"%ADB%" wait-for-device
set ROOT=non
for /f "delims=" %%i in ('"%ADB%" shell id') do set ID=%%i
echo !ID! | find "uid=0" >nul
if not errorlevel 1 set ROOT=oui
echo Root : !ROOT!

for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmm"') do set D=%%d
set OUT=%~dp0..\autoradio\dumps\SanderoDump_%D%
mkdir "%OUT%" 2>nul
echo Dossier : %OUT%
echo.

echo [1/8] Informations ^(getprop, applis, partitions, memoire^)...
"%ADB%" shell getprop > "%OUT%\getprop.txt"
"%ADB%" shell pm list packages -f > "%OUT%\packages.txt"
"%ADB%" shell pm list features > "%OUT%\features.txt"
"%ADB%" shell dumpsys package > "%OUT%\dumpsys_package.txt"
"%ADB%" shell dumpsys activity > "%OUT%\dumpsys_activity.txt"
"%ADB%" shell cat /proc/cpuinfo > "%OUT%\cpuinfo.txt"
"%ADB%" shell cat /proc/meminfo > "%OUT%\meminfo.txt"
"%ADB%" shell cat /proc/mounts > "%OUT%\mounts.txt"
"%ADB%" shell cat /proc/partitions > "%OUT%\partitions_proc.txt"
"%ADB%" shell ls -l /dev/block/by-name > "%OUT%\partitions.txt" 2>nul
"%ADB%" shell settings list global > "%OUT%\settings_global.txt"
"%ADB%" shell settings list secure > "%OUT%\settings_secure.txt"
"%ADB%" shell settings list system > "%OUT%\settings_system.txt"
"%ADB%" shell df > "%OUT%\df.txt"

call :pull /system "system" 2 8
call :pull /vendor "vendor" 3 8
call :pull /etc "etc" 4 8
call :pull /oem "oem" 5 8
call :pull /product "product" 6 8
call :pull /sdcard "sdcard" 7 8
if "!ROOT!"=="oui" (
  call :pull /data/app "data_app" 8 8
  call :pull /data/system "data_system" 8 8
  call :pull /data/misc "data_misc" 8 8
)

echo.
echo Termine. Dossier : %OUT%
echo Les avertissements "failed to copy" sur les liens ou fichiers interdits sont normaux.
echo Ne pas ajouter ce dossier au depot Git ^(APK, identifiants, adresses MAC^).
start "" "%OUT%"
goto :fin

:pull
echo [%3/%4] Copie de %1 ...
"%ADB%" pull %1 "%OUT%\%~2" > "%OUT%\log_%~2.txt" 2>&1
powershell -NoProfile -Command "Get-Content '%OUT%\log_%~2.txt' -Tail 1"
exit /b

:fin
pause

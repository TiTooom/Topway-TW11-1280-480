@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
rem ------------------------------------------------------------------
rem  Image complete des partitions de l'autoradio, depuis le PC, par ADB.
rem  Prerequis : ADB active sur l'autoradio et cable relie au PC
rem  (ou ADB Wi-Fi : "adb connect IP:5555" avant de lancer ce script).
rem  L'autoradio est une build "eng" : "adb root" y fonctionne normalement.
rem ------------------------------------------------------------------

set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

echo.
echo === Sauvegarde de l'autoradio par ADB ===
"%ADB%" devices
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo Aucun autoradio detecte. Verifier le cable et le debogage USB.
  pause
  exit /b 1
)

echo Passage en mode root...
"%ADB%" root
timeout /t 4 >nul
"%ADB%" wait-for-device
for /f "delims=" %%i in ('"%ADB%" shell id') do set ID=%%i
echo !ID! | find "uid=0" >nul
if errorlevel 1 (
  echo ADB n'est pas root : impossible de lire les partitions. Abandon.
  pause
  exit /b 1
)

for /f "tokens=1-3 delims=/:. " %%a in ("%date%") do set D=%%c%%b%%a
set OUT=%~dp0SanderoImage_PC_%D%_%time:~0,2%%time:~3,2%
set OUT=%OUT: =0%
mkdir "%OUT%"
echo Dossier : %OUT%

"%ADB%" shell ls -l /dev/block/by-name > "%OUT%\partitions.txt"
"%ADB%" shell getprop > "%OUT%\getprop.txt"

for /f "delims=" %%p in ('"%ADB%" shell ls /dev/block/by-name') do (
  set P=%%p
  set P=!P: =!
  if /i not "!P!"=="UDISK" (
    echo Copie de !P! ...
    "%ADB%" exec-out dd if=/dev/block/by-name/!P! bs=4194304 2^>/dev/null > "%OUT%\!P!.img"
    "%ADB%" shell md5sum /dev/block/by-name/!P! >> "%OUT%\MD5_autoradio.txt"
  )
)

echo.
echo Controle MD5 (a comparer avec MD5_autoradio.txt) :
for %%f in ("%OUT%\*.img") do certutil -hashfile "%%f" MD5 | find /v "hash" >> "%OUT%\MD5_pc.txt"
type "%OUT%\MD5_pc.txt"

echo.
echo Termine. La partition UDISK (/data, ~26 Go) n'est pas copiee.
echo Pour l'ajouter : adb exec-out dd if=/dev/block/by-name/UDISK bs=4194304 ^> UDISK.img
pause

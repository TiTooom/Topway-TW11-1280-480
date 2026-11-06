@echo off
rem Lance Sandero sur l'emulateur Android, sans Android Studio.
rem Ecran de l'autoradio (1280x480), carte graphique du PC, ecran toujours allume.
rem Usage : double-clic. Fermer avec une touche dans cette fenetre (remet l'ecran normal).
setlocal
set SDK=%LOCALAPPDATA%\Android\Sdk
set ADB=%SDK%\platform-tools\adb.exe
set AVD=Medium_Phone_API_37.0
rem APK le plus recent : release\Sandero_VXXX.apk (tri par nom, XXX sur 3 chiffres)
set APK=
for /f "delims=" %%f in ('dir /b /o-n "%~dp0..\release\Sandero_V*.apk" 2^>nul') do if not defined APK set "APK=%~dp0..\release\%%f"

if not exist "%ADB%" (echo adb introuvable : %ADB% & pause & exit /b 1)
if not defined APK (echo Aucun release\Sandero_V*.apk : compiler d'abord & pause & exit /b 1)
echo APK : %APK%

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo Demarrage de l'emulateur %AVD%...
  start "" "%SDK%\emulator\emulator.exe" -avd %AVD% -gpu host
) else (
  echo Emulateur deja lance.
)

"%ADB%" wait-for-device
echo Attente de la fin du demarrage d'Android...
:boot
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul
if errorlevel 1 (timeout /t 2 /nobreak >nul & goto boot)

echo Installation de Sandero...
"%ADB%" install -r "%APK%"
"%ADB%" shell svc power stayon true
"%ADB%" shell input keyevent KEYCODE_WAKEUP
"%ADB%" shell wm dismiss-keyguard
"%ADB%" shell wm size 1280x480
"%ADB%" shell wm density 160
"%ADB%" shell am start -n fr.sandero.launcher/.MainActivity

echo.
echo Sandero tourne en 1280x480.
echo Simulations : appui long sur la voiture ^> Outils ^> Moniteur TWUtil ^> Simuler phares / marche arriere.
echo.
echo Appuyez sur une touche pour remettre l'ecran normal de l'emulateur.
pause >nul
"%ADB%" shell wm size reset
"%ADB%" shell wm density reset
echo Ecran remis a la normale. L'emulateur reste ouvert.
timeout /t 3 >nul

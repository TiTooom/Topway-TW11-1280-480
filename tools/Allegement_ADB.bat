@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
rem ------------------------------------------------------------------
rem  Allegement de l'autoradio par ADB (sans root, reversible).
rem  Desactive les paquets de tools\allegement_liste.txt (pm disable-user),
rem  accelere les animations, et mesure la memoire avant / apres.
rem  Pour tout remettre : tools\Allegement_Annuler.bat
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
echo === Allegement de l'autoradio ===
echo Sur l'autoradio : Sandero, Outils, Systeme et demarrage, "Essayer d'ouvrir ADB par Wi-Fi".
set /p IP=Adresse IP de l'autoradio [%IP%] :
"%ADB%" connect %IP%:5555
"%ADB%" get-state >nul 2>&1
if errorlevel 1 ( echo Pas de connexion ADB. Accepter la fenetre RSA puis recommencer. & pause & exit /b 1 )

echo.>>"%LOG%"
echo ===== ALLEGEMENT %DATE% %TIME% =====>>"%LOG%"
call :mesure AVANT

echo.
echo Etat actuel des paquets enregistre (pour comparaison).
"%ADB%" shell pm list packages -d > "%OUT%\desactives_avant.txt"

set /a N=0, E=0
for /f "usebackq eol=# tokens=1 delims=; " %%P in ("%LISTE%") do (
  "%ADB%" shell pm disable-user --user 0 %%P > "%OUT%\r.txt" 2>&1
  findstr /i "disabled" "%OUT%\r.txt" >nul
  if errorlevel 1 (
    set /a E+=1
    for /f "usebackq delims=" %%r in ("%OUT%\r.txt") do echo   ECHEC %%P : %%r
    for /f "usebackq delims=" %%r in ("%OUT%\r.txt") do echo [ALLEGEMENT] ECHEC %%P : %%r>>"%LOG%"
  ) else (
    set /a N+=1
    echo   desactive %%P
    echo [ALLEGEMENT] desactive %%P>>"%LOG%"
  )
)
del "%OUT%\r.txt" 2>nul

echo.
echo Animations a 0,5x (au lieu de 1x)...
"%ADB%" shell settings put global window_animation_scale 0.5
"%ADB%" shell settings put global transition_animation_scale 0.5
"%ADB%" shell settings put global animator_duration_scale 0.5
echo [ALLEGEMENT] animations 0.5x>>"%LOG%"

echo.
echo %N% paquets desactives, %E% echecs. Redemarrage conseille pour liberer la memoire.
set /p RB=Redemarrer l'autoradio maintenant ? (O/N) :
if /i "%RB%"=="O" (
  "%ADB%" reboot
  echo Apres le redemarrage : rouvrir l'ADB Wi-Fi depuis Sandero, puis appuyer sur une touche.
  pause
  "%ADB%" connect %IP%:5555
  "%ADB%" get-state >nul 2>&1 && call :mesure APRES
) else (
  call :mesure APRES_SANS_REDEMARRAGE
)
echo Journal et mesures dans "%OUT%".
pause
exit /b 0

rem --- Mesure memoire et processus ------------------------------------
:mesure
set M=%OUT%\mesure_%1.txt
echo ===== %1 %DATE% %TIME% =====> "%M%"
"%ADB%" shell dumpsys meminfo | findstr /i /c:"Total RAM" /c:"Free RAM" /c:"Used RAM" /c:"Lost RAM" >>"%M%"
echo --- nombre de processus --->>"%M%"
"%ADB%" shell "ps -A | wc -l" >>"%M%"
echo --- 15 processus les plus gourmands (memoire) --->>"%M%"
"%ADB%" shell dumpsys meminfo | findstr /r /c:"K: " | findstr /v /i "Total" > "%OUT%\pss.txt"
for /f "usebackq skip=0 tokens=*" %%l in ("%OUT%\pss.txt") do (
  set /a C+=1
  if !C! LEQ 15 echo %%l>>"%M%"
)
set C=0
del "%OUT%\pss.txt" 2>nul
type "%M%"
type "%M%">>"%LOG%"
exit /b 0

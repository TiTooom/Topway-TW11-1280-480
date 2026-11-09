@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
rem ------------------------------------------------------------------
rem  Recuperer l'image d'origine des partitions de l'autoradio (boot,
rem  recovery, bootloader, env...) SANS RIEN ECRIRE sur l'autoradio.
rem  Methode : demarrer UNE fois un boot Magisk en memoire (fastboot boot),
rem  puis copier les partitions avec ce root temporaire.
rem  Voir le skill .claude/skills/topway-image-systeme.
rem
rem  Ce script n'utilise JAMAIS "fastboot flash" ni "fastboot erase".
rem  En cas de souci : couper puis remettre le contact = etat d'origine.
rem ------------------------------------------------------------------

set ROOT=%~dp0..
set PT=%LOCALAPPDATA%\Android\Sdk\platform-tools
set ADB=%PT%\adb.exe
set FB=%PT%\fastboot.exe
if not exist "%ADB%" set ADB=adb
if not exist "%FB%" set FB=fastboot
set MAGISK=%ROOT%\tools\externes\Magisk-v31.1.apk
set BOOTFW=%ROOT%\autoradio\firmware_T3-P1_THEME1_20231116\boot.img
set OUT=%ROOT%\autoradio\images
if not exist "%OUT%" mkdir "%OUT%"
set LOG=%OUT%\suivi_boot_origine.txt
set IP=192.168.0.103

echo.>>"%LOG%"
echo ===== %DATE% %TIME% =====>>"%LOG%"

:menu
echo.
echo ================ Image d'origine de l'autoradio ================
echo  Sorties : %OUT%
echo.
echo  1. Releves sans risque (ADB Wi-Fi)
echo  2. Tester fastboot (redemarre en mode bootloader, n'ecrit rien)
echo  3. Preparer Magisk (installe l'appli, envoie boot.img a patcher)
echo  4. Recuperer le boot patche sur le PC
echo  5. Demarrer le boot patche EN MEMOIRE (fastboot boot)
echo  6. Copier toutes les partitions (sauf UDISK) + controle MD5
echo  0. Quitter
echo.
set CH=
set /p CH=Choix :
if "%CH%"=="1" goto releves
if "%CH%"=="2" goto fastboot
if "%CH%"=="3" goto magisk
if "%CH%"=="4" goto recup
if "%CH%"=="5" goto bootram
if "%CH%"=="6" goto copie
if "%CH%"=="0" exit /b 0
goto menu

rem ---------------------------------------------------------------
:connecter
echo Sur l'autoradio : Sandero, Outils, Systeme et demarrage,
echo "Essayer d'ouvrir ADB par Wi-Fi".
set /p IP=Adresse IP de l'autoradio [%IP%] :
"%ADB%" connect %IP%:5555
"%ADB%" connect %IP%:5555 >>"%LOG%" 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo Pas de connexion. Accepter la fenetre RSA sur l'ecran puis recommencer.
  echo [ADB] connexion %IP% echouee>>"%LOG%"
  exit /b 1
)
echo [ADB] connecte a %IP%>>"%LOG%"
exit /b 0

rem ---------------------------------------------------------------
:releves
call :connecter || goto menu
set R=%OUT%\releves.txt
echo --- id ---> "%R%"
"%ADB%" shell id >>"%R%" 2>&1
echo --- /proc/cmdline --->>"%R%"
"%ADB%" shell cat /proc/cmdline >>"%R%" 2>&1
echo --- by-name --->>"%R%"
"%ADB%" shell ls -l /dev/block/by-name/ >>"%R%" 2>&1
echo --- getprop boot/fastboot --->>"%R%"
"%ADB%" shell getprop | findstr /i "boot fastboot bootloader" >>"%R%" 2>&1
echo Releves enregistres dans "%R%"
echo ATTENTION : ce fichier peut contenir le numero de serie : il reste hors depot (.gitignore).
echo [RELEVES] faits>>"%LOG%"
type "%R%"
pause
goto menu

rem ---------------------------------------------------------------
:fastboot
call :connecter || goto menu
echo.
echo Relier le PC a la prise USB de l'autoradio avec le cable.
echo L'autoradio va redemarrer en mode bootloader. Rien n'est ecrit.
echo Si rien ne se passe : couper puis remettre le contact.
pause
"%ADB%" reboot bootloader
echo [FASTBOOT] adb reboot bootloader envoye>>"%LOG%"
call :attendre_fastboot
if errorlevel 1 (
  echo Aucun appareil fastboot vu en 60 s.
  echo - Gestionnaire de peripheriques : un "peripherique inconnu" ? Installer WinUSB
  echo   avec tools\externes\zadig-2.9.exe, puis relancer ce choix.
  echo - Rien du tout : pas de fastboot sur ce modele, passer au mode FEL.
  echo [FASTBOOT] aucun appareil en 60 s>>"%LOG%"
) else (
  echo FASTBOOT DISPONIBLE.
  "%FB%" getvar all >>"%LOG%" 2>&1
  echo Infos fastboot ajoutees au journal. Pour redemarrer normalement :
  echo   fastboot reboot   (ou couper/remettre le contact^)
)
pause
goto menu

:attendre_fastboot
for /l %%i in (1,1,30) do (
  "%FB%" devices 2>nul | findstr /r "." >nul && (
    "%FB%" devices
    "%FB%" devices >>"%LOG%"
    exit /b 0
  )
  timeout /t 2 >nul
)
exit /b 1

rem ---------------------------------------------------------------
:magisk
if not exist "%MAGISK%" ( echo Manque "%MAGISK%" & pause & goto menu )
if not exist "%BOOTFW%" ( echo Manque "%BOOTFW%" & pause & goto menu )
call :connecter || goto menu
echo Installation de Magisk...
"%ADB%" install -r "%MAGISK%"
"%ADB%" install -r "%MAGISK%" >>"%LOG%" 2>&1
"%ADB%" shell mkdir -p /sdcard/Download
"%ADB%" push "%BOOTFW%" /sdcard/Download/boot.img
echo [MAGISK] appli installee, boot.img envoye>>"%LOG%"
echo.
echo Sur l'autoradio : ouvrir Magisk, Installer, "Selectionner et patcher un fichier",
echo choisir Download/boot.img. Attendre "Termine", puis choix 4.
pause
goto menu

rem ---------------------------------------------------------------
:recup
call :connecter || goto menu
set PF=
for /f "delims=" %%f in ('"%ADB%" shell ls -t /sdcard/Download/ ^| findstr magisk_patched') do if not defined PF set PF=%%f
if not defined PF ( echo Aucun magisk_patched dans /sdcard/Download. & pause & goto menu )
"%ADB%" pull /sdcard/Download/%PF% "%OUT%\boot_magisk.img"
echo [MAGISK] %PF% recupere en boot_magisk.img>>"%LOG%"
echo Boot patche : "%OUT%\boot_magisk.img"
pause
goto menu

rem ---------------------------------------------------------------
:bootram
if not exist "%OUT%\boot_magisk.img" ( echo Faire d'abord les choix 3 et 4. & pause & goto menu )
echo.
echo Demarrage du boot Magisk EN MEMOIRE uniquement (fastboot boot).
echo Rien n'est ecrit. Le prochain redemarrage remet le boot d'origine.
set /p OK=Taper OUI pour continuer :
if /i not "%OK%"=="OUI" goto menu
"%FB%" devices 2>nul | findstr /r "." >nul
if errorlevel 1 (
  call :connecter || goto menu
  "%ADB%" reboot bootloader
  call :attendre_fastboot || ( echo Pas de fastboot. & pause & goto menu )
)
"%FB%" boot "%OUT%\boot_magisk.img"
"%FB%" boot "%OUT%\boot_magisk.img" >>"%LOG%" 2>&1
echo [BOOTRAM] fastboot boot boot_magisk.img lance>>"%LOG%"
echo.
echo Si l'autoradio demarre :
echo  1. Rouvrir l'ADB Wi-Fi depuis Sandero (le port est perdu au redemarrage).
echo  2. Ouvrir Magisk : il doit indiquer "Installe". Onglet Superutilisateur :
echo     autoriser "Shell" (Magisk v31 ne pose plus la question tout seul).
echo  3. Choix 6.
echo S'il ne demarre pas apres 3 minutes : couper puis remettre le contact.
pause
goto menu

rem ---------------------------------------------------------------
:copie
call :connecter || goto menu
"%ADB%" shell su -c id > "%OUT%\su.txt" 2>&1
findstr "uid=0" "%OUT%\su.txt" >nul
if errorlevel 1 (
  type "%OUT%\su.txt"
  echo Pas de root : demarrer d'abord le boot Magisk (choix 5^) et autoriser Shell dans Magisk.
  echo [COPIE] pas de root>>"%LOG%"
  pause
  goto menu
)
del "%OUT%\su.txt"
"%ADB%" shell su -c "ls -1 /dev/block/by-name" > "%OUT%\partitions.txt"
echo mmcblk0boot0>>"%OUT%\partitions.txt"
echo mmcblk0boot1>>"%OUT%\partitions.txt"
for /f "usebackq tokens=*" %%P in ("%OUT%\partitions.txt") do (
  set P=%%P
  if /i not "!P!"=="UDISK" if not "!P!"=="" (
    set SRC=/dev/block/by-name/!P!
    if "!P:~0,7!"=="mmcblk0" set SRC=/dev/block/!P!
    echo Copie de !P! ...
    "%ADB%" exec-out su -c "dd if=!SRC! bs=4194304 2>/dev/null" > "%OUT%\!P!.img"
    set MD=
    for /f "tokens=1" %%m in ('"%ADB%" shell su -c "md5sum !SRC!"') do set MD=%%m
    set MP=
    for /f "skip=1 tokens=*" %%h in ('certutil -hashfile "%OUT%\!P!.img" MD5 ^| findstr /v "CertUtil"') do if not defined MP set MP=%%h
    set MP=!MP: =!
    if /i "!MD!"=="!MP!" (
      echo   OK  !MD!
      echo [COPIE] !P! OK md5 !MD!>>"%LOG%"
    ) else (
      echo   ERREUR MD5 : autoradio !MD! / PC !MP!
      echo [COPIE] !P! ERREUR md5 autoradio !MD! PC !MP!>>"%LOG%"
    )
  )
)
echo.
echo Termine. Images dans "%OUT%" (env, private, misc... restent hors depot : identifiants).
echo Couper puis remettre le contact pour revenir au boot d'origine.
pause
goto menu

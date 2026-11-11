@echo off
rem Compile / televerse le projet Elecrow5 avec PlatformIO.
rem ESP-IDF refuse les chemins qui contiennent un espace ("SOFT SANDERO") : le script
rem monte le dossier parent sur un lecteur virtuel (subst) le temps de la commande.
rem
rem   Compiler.bat            compile
rem   Compiler.bat upload     compile et televerse (carte branchee en USB)
rem   Compiler.bat monitor    moniteur serie (journal ESP_LOG, Ctrl+C pour quitter)
rem   Compiler.bat clean      efface la compilation

setlocal
set "PIO=%USERPROFILE%\.platformio\penv\Scripts\pio.exe"
if not exist "%PIO%" (
    echo PlatformIO introuvable : %PIO%
    exit /b 1
)

for %%i in ("%~dp0.") do set "NOM=%%~nxi"
for %%i in ("%~dp0..") do set "PARENT=%%~fi"

set "L="
for %%d in (X W V U T S R) do if not defined L if not exist %%d:\ set "L=%%d"
if not defined L (
    echo Aucune lettre de lecteur libre pour subst.
    exit /b 1
)

subst %L%: "%PARENT%"
pushd %L%:\%NOM%
if /i "%~1"=="upload"  ( "%PIO%" run -t upload
) else if /i "%~1"=="monitor" ( "%PIO%" device monitor
) else if /i "%~1"=="clean"   ( "%PIO%" run -t clean
) else ( "%PIO%" run )
set "CODE=%errorlevel%"
popd
subst %L%: /d
exit /b %CODE%

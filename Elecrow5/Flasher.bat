@echo off
rem Double-clic : compile, televerse sur la carte (USB), puis affiche le journal serie.
rem Les boutons PlatformIO de VS Code ne marchent pas ici (espace dans "SOFT SANDERO").
call "%~dp0Compiler.bat" upload
if errorlevel 1 ( echo. & echo Echec du televersement : carte branchee ? & pause & exit /b 1 )
call "%~dp0Compiler.bat" monitor

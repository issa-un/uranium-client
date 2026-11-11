@echo off
rem Starts Uranium. It ships with its own Java 25 (the runtime folder next to this file);
rem without it, it looks for the Java 25 that comes with the Minecraft Launcher.
setlocal
set "JAR=%~dp0uranium.jar"
set "RT=runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon\bin\javaw.exe"

rem The Java that comes with Uranium
set "JAVAW=%~dp0runtime\bin\javaw.exe"
if exist "%JAVAW%" goto run
rem Microsoft Store / Xbox app launcher
set "JAVAW=%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\%RT%"
if exist "%JAVAW%" goto run
rem Classic launcher (installed for all users, or in .minecraft)
set "JAVAW=%ProgramFiles(x86)%\Minecraft Launcher\%RT%"
if exist "%JAVAW%" goto run
set "JAVAW=%ProgramFiles%\Minecraft Launcher\%RT%"
if exist "%JAVAW%" goto run
set "JAVAW=%APPDATA%\.minecraft\%RT%"
if exist "%JAVAW%" goto run
rem CurseForge and Prism Launcher keep the same Java under their own folders
set "JAVAW=%USERPROFILE%\curseforge\minecraft\Install\%RT%"
if exist "%JAVAW%" goto run
set "JAVAW=%APPDATA%\PrismLauncher\java\java-runtime-epsilon\bin\javaw.exe"
if exist "%JAVAW%" goto run
rem Your own Java 25 or newer, if JAVA_HOME points at it
set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if defined JAVA_HOME if exist "%JAVAW%" goto run

echo Could not find Java 25.
echo The "runtime" folder that comes with Uranium is missing. Unzip the whole Uranium.zip
echo (not just Start.bat) and run Start.bat from the unzipped folder.
pause
exit /b 1

:run
start "" "%JAVAW%" --add-modules=jdk.attach -jar "%JAR%"

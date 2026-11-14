@echo off
rem Double-click launcher. Needs Java 21+ (built with JDK 26).
rem Plain double-clicking LocalTransfer.jar fails here because Windows opens .jar files with Java 8.
setlocal
set "JAR=%~dp0LocalTransfer.jar"
if not exist "%JAR%" set "JAR=%~dp0target\LocalTransfer.jar"
if not exist "%JAR%" (
  echo Cannot find LocalTransfer.jar - run "mvn package" first.
  pause
  exit /b 1
)
set "JW=javaw"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JW=%JAVA_HOME%\bin\javaw.exe"
start "" "%JW%" -jar "%JAR%" %*
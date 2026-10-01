@echo off
setlocal
if not defined JAVA_HOME (
  for /d %%D in ("%~dp0.tools\jdk-*") do set "JAVA_HOME=%%~fD"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=%~dp0.gradle"
if not exist "%~dp0.tools\gradle-8.13\bin\gradle.bat" (
  echo Run python tools\setup_android.py first, or use Android Studio's Gradle installation.
  exit /b 1
)
call "%~dp0.tools\gradle-8.13\bin\gradle.bat" %*

@echo off
setlocal
set "GRADLE_VERSION=8.8"
set "BOOTSTRAP=%~dp0.gradle-bootstrap"
set "ZIP=%BOOTSTRAP%\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST=%BOOTSTRAP%\gradle-%GRADLE_VERSION%"

if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%BOOTSTRAP%" mkdir "%BOOTSTRAP%"
  if not exist "%ZIP%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
    if errorlevel 1 exit /b 1
  )
  echo Extracting Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%BOOTSTRAP%' -Force"
  if errorlevel 1 exit /b 1
)

call "%DIST%\bin\gradle.bat" %*
exit /b %errorlevel%

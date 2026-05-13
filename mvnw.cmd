@echo off
rem ------------------------------------------------------------------
rem bromo Maven wrapper (Windows).
rem Bootstraps Apache Maven into %USERPROFILE%\.m2\bootstrap on first
rem run, then delegates to it. Requires JAVA_HOME set, or Temurin in
rem its default JetBrains location.
rem ------------------------------------------------------------------
setlocal enabledelayedexpansion
set MVN_VER=3.9.9
set MVN_HOME=%USERPROFILE%\.m2\bootstrap\apache-maven-%MVN_VER%

if "%JAVA_HOME%"=="" (
  if exist "%USERPROFILE%\.jdks\temurin-25.0.3" set JAVA_HOME=%USERPROFILE%\.jdks\temurin-25.0.3
)
if "%JAVA_HOME%"=="" (
  echo Error: JAVA_HOME is not set and no Temurin 25 was found at the default location. 1>&2
  exit /b 1
)

if not exist "%MVN_HOME%\bin\mvn.cmd" (
  echo Bootstrapping Apache Maven %MVN_VER%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $dest=Join-Path $env:USERPROFILE '.m2\bootstrap'; New-Item -ItemType Directory -Force -Path $dest | Out-Null; $zip=Join-Path $env:TEMP 'apache-maven-%MVN_VER%-bin.zip'; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VER%/apache-maven-%MVN_VER%-bin.zip' -OutFile $zip; Expand-Archive -Force -Path $zip -DestinationPath $dest; Remove-Item $zip -Force"
  if errorlevel 1 exit /b 1
)

call "%MVN_HOME%\bin\mvn.cmd" %*
endlocal

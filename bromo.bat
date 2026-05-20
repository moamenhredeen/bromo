@echo off
rem ------------------------------------------------------------------
rem bromo launcher (Windows).
rem Wraps `java -jar target\bromo.jar --stdio` with the JVM flags the
rem server expects:
rem  --enable-preview  StructuredTaskScope / ScopedValue (Java 25 preview)
rem  -XX:+UseZGC       Generational ZGC (default flavour in 25)
rem  -XX:AOTMode=auto  JEP 514 one-step AOT: cache produced on first
rem                    run, consumed on every subsequent run.
rem  -XX:AOTCache=...  Cache path. Lives under target\ so `mvn clean`
rem                    drops it.
rem ------------------------------------------------------------------
setlocal
set SCRIPT_DIR=%~dp0
set JAR=%SCRIPT_DIR%target\bromo.jar
set AOT_CACHE=%SCRIPT_DIR%target\bromo.aot

if "%JAVA_HOME%"=="" (
  if exist "%USERPROFILE%\.jdks\temurin-25.0.3" set JAVA_HOME=%USERPROFILE%\.jdks\temurin-25.0.3
)
if "%JAVA_HOME%"=="" (
  echo Error: JAVA_HOME not set and no Temurin 25 found at the default location. 1>&2
  exit /b 1
)

if not exist "%JAR%" (
  echo Error: %JAR% not found. Build it first: 1>&2
  echo   .\mvnw.cmd -DskipTests package 1>&2
  exit /b 1
)

"%JAVA_HOME%\bin\java.exe" ^
  --enable-preview ^
  -XX:+UseZGC ^
  -XX:AOTMode=auto ^
  -XX:AOTCache=%AOT_CACHE% ^
  -jar "%JAR%" %*

endlocal

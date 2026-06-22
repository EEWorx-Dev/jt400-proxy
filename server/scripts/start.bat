@echo off
setlocal enabledelayedexpansion

REM jt400-proxy-server start wrapper (Windows)

set SCRIPT_DIR=%~dp0

REM Determine server root
for %%I in ("%SCRIPT_DIR%") do set BASENAME=%%~nI
if "%BASENAME%"=="scripts" (
  set SERVER_ROOT=%SCRIPT_DIR%..
) else if "%BASENAME%"=="bin" (
  set SERVER_ROOT=%SCRIPT_DIR%..
) else (
  set SERVER_ROOT=%SCRIPT_DIR%
)
cd /d %SERVER_ROOT%

REM Default location for env file (in conf\ for cleaner distributions)
set DEFAULT_JT400_ENV_FILE=conf\env.bat

REM Determine JT400_ENV_FILE:
REM - If supplied as parameter using explicit '--env' or '--env-file', use that value.
REM - Otherwise default to DEFAULT_JT400_ENV_FILE.
REM Bare first argument that is an existing file is also accepted as override.
set JT400_ENV_FILE=
if not "%~1"=="" (
  if exist "%~1" (
    set JT400_ENV_FILE=%~1
    shift
  ) else if /i "%~1"=="--env" (
    if not "%~2"=="" (
      set JT400_ENV_FILE=%~2
      shift
      shift
    )
  ) else if /i "%~1"=="--env-file" (
    if not "%~2"=="" (
      set JT400_ENV_FILE=%~2
      shift
      shift
    )
  )
)

if not defined JT400_ENV_FILE (
  set JT400_ENV_FILE=%DEFAULT_JT400_ENV_FILE%
)

REM Load environment file
set LOADED_ENV=
if defined JT400_ENV_FILE (
  if exist "%JT400_ENV_FILE%" (
    echo Loading environment from %JT400_ENV_FILE%
    call "%JT400_ENV_FILE%"
    set LOADED_ENV=1
  ) else (
    if /i not "%JT400_ENV_FILE%"=="%DEFAULT_JT400_ENV_FILE%" (
      echo WARNING: Specified env file not found: %JT400_ENV_FILE%
    )
  )
)

REM If we fell back to the default and nothing was loaded, also try the traditional
REM root-level env.bat (for source tree + previous documented usage).
if not defined LOADED_ENV (
  if /i "%JT400_ENV_FILE%"=="%DEFAULT_JT400_ENV_FILE%" (
    if exist env.bat (
      echo Loading environment from env.bat
      call env.bat
    )
  )
)

REM Locate JAR
set JAR=
for %%f in (lib\jt400-proxy-server*.jar) do set JAR=%%f
if "%JAR%"=="" (
  for %%f in (target\jt400-proxy-server-*.jar) do set JAR=%%f
)

if "%JAR%"=="" (
  echo ERROR: Could not find jt400-proxy-server*.jar
  echo        In a distribution it should be in lib\
  echo        In source tree, run: mvn clean package
  pause
  exit /b 1
)

if not defined PROXY_TCP_PORT set PROXY_TCP_PORT=9400
if not defined HIKARI_MAX_POOL_SIZE set HIKARI_MAX_POOL_SIZE=20

set JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC

if not exist logs mkdir logs

REM Stop previous if running
if exist logs\proxy.pid (
  set /p OLD_PID=<logs\proxy.pid
  taskkill /PID !OLD_PID! /F >nul 2>&1 || echo Previous process not found.
  del /q logs\proxy.pid >nul 2>&1
)

echo Starting jt400-proxy-server...
echo   JAR: %JAR%
echo   TCP port: %PROXY_TCP_PORT%
echo   Hikari max pool: %HIKARI_MAX_POOL_SIZE%

start "jt400-proxy-server" /B java %JAVA_OPTS% ^
  -Dproxy.tcp.port=%PROXY_TCP_PORT% ^
  -Dhikari.maxPoolSize=%HIKARI_MAX_POOL_SIZE% ^
  -jar "%JAR%" %* > logs\proxy.out 2> logs\proxy.err

for /f "tokens=2" %%i in ('tasklist /fi "imagename eq java.exe" /fo list ^| find "PID:"') do (
  echo %%i > logs\proxy.pid
  goto :started
)

:started
echo Started.
echo Use stop.bat to stop.
pause

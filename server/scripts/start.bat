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

REM Load env.bat if present
if exist env.bat (
  call env.bat
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

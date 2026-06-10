@echo off
setlocal enabledelayedexpansion

REM jt400-proxy-server launcher (Windows)
REM Set AS400_* and HIKARI_* env vars before running, or edit below.

set SCRIPT_DIR=%~dp0
cd /d %SCRIPT_DIR%

set JAR=
for %%f in (target\jt400-proxy-server-*.jar) do set JAR=%%f

if "%JAR%"=="" (
  echo ERROR: No jar found in target\. Run "mvn clean package" first.
  pause
  exit /b 1
)

if not defined PROXY_TCP_PORT set PROXY_TCP_PORT=9400
if not defined HIKARI_MAX_POOL_SIZE set HIKARI_MAX_POOL_SIZE=20

set JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC

echo Starting jt400-proxy-server...
echo   JAR: %JAR%
echo   TCP port: %PROXY_TCP_PORT%
echo   Hikari max pool: %HIKARI_MAX_POOL_SIZE%
echo.

java %JAVA_OPTS% ^
  -Dproxy.tcp.port=%PROXY_TCP_PORT% ^
  -Dhikari.maxPoolSize=%HIKARI_MAX_POOL_SIZE% ^
  -jar "%JAR%" %*

pause

@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0

for %%I in ("%SCRIPT_DIR%") do set BASENAME=%%~nI
if "%BASENAME%"=="scripts" (
  set SERVER_ROOT=%SCRIPT_DIR%..
) else if "%BASENAME%"=="bin" (
  set SERVER_ROOT=%SCRIPT_DIR%..
) else (
  set SERVER_ROOT=%SCRIPT_DIR%
)
cd /d %SERVER_ROOT%

if exist env.bat (
  call env.bat
)

set JAR=
for %%f in (lib\jt400-proxy-server*.jar) do set JAR=%%f
if "%JAR%"=="" (
  for %%f in (target\jt400-proxy-server-*.jar) do set JAR=%%f
)

if "%JAR%"=="" (
  echo ERROR: No jar found. Run "mvn clean package" first or use a distribution.
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

java %JAVA_OPTS% ^
  -Dproxy.tcp.port=%PROXY_TCP_PORT% ^
  -Dhikari.maxPoolSize=%HIKARI_MAX_POOL_SIZE% ^
  -jar "%JAR%" %*

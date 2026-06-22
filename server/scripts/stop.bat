@echo off
setlocal

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

REM Accept (and ignore) optional env file argument for symmetry with start/run
REM e.g. stop.bat, stop.bat prod.bat, stop.bat --env prod.bat
if not "%~1"=="" (
  if exist "%~1" (
    shift
  ) else if /i "%~1"=="--env" (
    shift
    if not "%~1"=="" shift
  ) else if /i "%~1"=="--env-file" (
    shift
    if not "%~1"=="" shift
  )
)

if exist logs\proxy.pid (
  set /p PID=<logs\proxy.pid
  echo Stopping jt400-proxy-server (PID %PID%)...
  taskkill /PID %PID% /F >nul 2>&1
  if errorlevel 1 (
    echo Process not found or already stopped.
  ) else (
    echo Stopped.
  )
  del /q logs\proxy.pid >nul 2>&1
) else (
  echo No PID file found.
  wmic process where "name='java.exe' and commandline like '%%jt400-proxy-server%%'" delete >nul 2>&1 || echo No matching process found.
)

echo Done.
pause

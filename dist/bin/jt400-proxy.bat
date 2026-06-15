@echo off
setlocal enabledelayedexpansion

REM jt400-proxy.bat - Windows companion for the jt400-proxy dispatcher
REM Mirrors the Unix bin/jt400-proxy for the combined distribution.

set "SCRIPT_DIR=%~dp0"
set "DIST_ROOT=%SCRIPT_DIR:~0,-1%"
for %%i in ("%DIST_ROOT%") do set "DIST_ROOT=%%~dpi"
set "DIST_ROOT=%DIST_ROOT:~0,-1%"

set "COMPONENT=%~1"
shift

if "%COMPONENT%"=="" goto :usage

REM Auto-extract inner dists on first use (so full-dist gives expanded server/ and client/ after one extract)
call :maybe_extract server server\*-dist.tar.gz
call :maybe_extract client client\*-client-dist.tar.gz

if /i "%COMPONENT%"=="server" goto :server
if /i "%COMPONENT%"=="s" goto :server
if /i "%COMPONENT%"=="client" goto :client
if /i "%COMPONENT%"=="c" goto :client
goto :usage

:maybe_extract
set "SUB=%~1"
set "PATTERN=%~2"
if exist "%DIST_ROOT%\%SUB%\bin" goto :eof
for %%F in ("%DIST_ROOT%\%PATTERN%") do (
  if exist "%%F" (
    echo First use: extracting %%~nxF into %SUB%\ ...
    if not exist "%DIST_ROOT%\%SUB%" mkdir "%DIST_ROOT%\%SUB%"
    powershell -NoProfile -Command "Expand-Archive -Path '%%F' -DestinationPath '%DIST_ROOT%\%SUB%' -Force"
    REM The zip inside usually has a top-level folder; move contents up if needed
    for /d %%D in ("%DIST_ROOT%\%SUB%\*") do (
      if exist "%%D\bin" (
        robocopy "%%D" "%DIST_ROOT%\%SUB%" /E /MOVE >nul
      )
    )
  )
)
goto :eof

:server
set "SERVER_DIR=%DIST_ROOT%\server"
if not exist "%SERVER_DIR%" (
  echo Error: server\ directory not found in this distribution
  exit /b 1
)

set "SUBCMD=%~1"
shift

if /i "%SUBCMD%"=="start" (
  call "%SERVER_DIR%\bin\start.bat" %*
  goto :eof
)
if /i "%SUBCMD%"=="stop" (
  call "%SERVER_DIR%\bin\stop.bat" %*
  goto :eof
)
if /i "%SUBCMD%"=="restart" (
  call "%SERVER_DIR%\bin\stop.bat" 2>nul || echo.
  timeout /t 1 >nul
  call "%SERVER_DIR%\bin\start.bat" %*
  goto :eof
)
if /i "%SUBCMD%"=="run" (
  if exist "%SERVER_DIR%\bin\run.bat" (
    call "%SERVER_DIR%\bin\run.bat" %*
  ) else (
    call "%SERVER_DIR%\bin\start.bat" %*
  )
  goto :eof
)
if /i "%SUBCMD%"=="status" (
  if exist "%SERVER_DIR%\logs\proxy.pid" (
    type "%SERVER_DIR%\logs\proxy.pid"
  ) else (
    echo No running server (no proxy.pid)
    exit /b 1
  )
  goto :eof
)
if /i "%SUBCMD%"=="logs" (
  if exist "%SERVER_DIR%\logs\proxy.out" (
    powershell -NoProfile -Command "Get-Content '%SERVER_DIR%\logs\proxy.out' -Wait -Tail 50"
  ) else (
    echo No log file found at %SERVER_DIR%\logs\proxy.out
    exit /b 1
  )
  goto :eof
)

echo Unknown 'server' subcommand: %SUBCMD%
echo Supported: start, stop, restart, run, status, logs
exit /b 1

:client
set "CLIENT_DIR=%DIST_ROOT%\client"
if not exist "%CLIENT_DIR%" (
  echo Error: client\ directory not found in this distribution
  exit /b 1
)

set "SUBCMD=%~1"
shift

if /i "%SUBCMD%"=="start" (
  cd /d "%CLIENT_DIR%"
  node bin\cli.js %*
  goto :eof
)
if /i "%SUBCMD%"=="stop" (
  REM Try PM2 first
  pm2 list 2>nul | findstr /C:"jt400-proxy-client" >nul
  if not errorlevel 1 (
    pm2 stop jt400-proxy-client 2>nul || echo.
    goto :eof
  )
  REM Otherwise try to kill by port (requires PowerShell)
  set "PORT=3456"
  if not "%~1"=="" set "PORT=%~1"
  powershell -NoProfile -Command ^
    "$port = %PORT%; " ^
    "$pids = (Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue).OwningProcess | Select-Object -Unique; " ^
    "if ($pids) { " ^
    "  Write-Host ('Stopping process(es) on port ' + $port + ': ' + ($pids -join ', ')); " ^
    "  Stop-Process -Id $pids -Force -ErrorAction SilentlyContinue " ^
    "} else { " ^
    "  Write-Host ('No process found listening on port ' + $port) " ^
    "}"
  goto :eof
)
if /i "%SUBCMD%"=="pm2-start" (
  REM --update-env tells PM2 to merge the current cmd environment (passthrough)
  pm2 start "%CLIENT_DIR%\ecosystem.config.js" --cwd "%CLIENT_DIR%" --update-env %*
  goto :eof
)
if /i "%SUBCMD%"=="pm2-stop" (
  pm2 stop jt400-proxy-client 2>nul || echo.
  goto :eof
)
if /i "%SUBCMD%"=="pm2-restart" (
  pm2 restart jt400-proxy-client 2>nul || echo.
  goto :eof
)
if /i "%SUBCMD%"=="pm2-logs" (
  pm2 logs jt400-proxy-client --lines 100 %*
  goto :eof
)
if /i "%SUBCMD%"=="restart" (
  pm2 list 2>nul | findstr /C:"jt400-proxy-client" >nul
  if not errorlevel 1 (
    pm2 restart jt400-proxy-client 2>nul || echo.
  ) else (
    call "%~f0" client stop 2>nul
    timeout /t 1 >nul
    call "%~f0" client start %*
  )
  goto :eof
)
if /i "%SUBCMD%"=="logs" (
  pm2 list 2>nul | findstr /C:"jt400-proxy-client" >nul
  if not errorlevel 1 (
    pm2 logs jt400-proxy-client --lines 100 %*
  ) else (
    if exist "%CLIENT_DIR%\logs" (
      powershell -NoProfile -Command "Get-Content '%CLIENT_DIR%\logs\*.log' -Wait -Tail 50" 2>nul || echo No log files or tail failed.
    ) else (
      echo No PM2 process and no logs\ dir found. Try 'jt400-proxy client pm2-logs'.
    )
  )
  goto :eof
)
if /i "%SUBCMD%"=="stats" (
  set "PORT=3456"
  if not "%~1"=="" set "PORT=%~1"
  set "URL=http://127.0.0.1:%PORT%/stats"
  powershell -NoProfile -Command "try { (Invoke-WebRequest -Uri '%URL%' -UseBasicParsing).Content } catch { Write-Error 'Failed to fetch stats. Is the client running?' }"
  goto :eof
)
if /i "%SUBCMD%"=="help" (
  echo jt400-proxy client commands:
  echo   start [options]     Start the standalone HTTP facade
  echo   stop [port]         Stop the client (PM2 if active, otherwise kill by port)
  echo   pm2-start [...]     Start via PM2 using the bundled ecosystem.config.js (inherits current env vars via --update-env)
  echo   pm2-stop            Stop the PM2-managed client
  echo   pm2-restart         Restart the PM2-managed client
  echo   pm2-logs            Show PM2 logs
  echo   restart             Restart (PM2 if active, otherwise direct)
  echo   logs                Show logs (PM2 if active, otherwise tail)
  echo   stats [port]        Fetch /stats from the running facade (default 3456)
  echo.
  echo All other flags for 'start' are forwarded to the client CLI.
  cd /d "%CLIENT_DIR%"
  node bin\cli.js --help 2>nul || echo (client help not available)
  goto :eof
)

echo Unknown 'client' subcommand: %SUBCMD%
echo Supported: start, stop, pm2-start, pm2-stop, pm2-restart, pm2-logs, restart, logs, stats, help
exit /b 1

:usage
echo jt400-proxy - Unified command for the jt400-proxy distribution (Windows)
echo.
echo Usage:
echo   jt400-proxy server ^<subcommand^> [args...]
echo   jt400-proxy client ^<subcommand^> [args...]
echo.
echo Server subcommands: start, stop, restart, run, status, logs
echo Client subcommands: start, stop, pm2-start (env passthrough via --update-env), pm2-stop, pm2-restart, pm2-logs, restart, logs, stats, help
echo.
echo Examples:
echo   jt400-proxy server start
echo   jt400-proxy server restart
echo   jt400-proxy client start --port 4000
echo   jt400-proxy client stop
echo   jt400-proxy client pm2-start --env production
echo   jt400-proxy client stats
exit /b 1

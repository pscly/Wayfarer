@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "RUN_DIR=%ROOT%.sisyphus\run"
set "BACKEND_PID_FILE=%RUN_DIR%\backend.pid"
set "WEB_PID_FILE=%RUN_DIR%\web.pid"

call :KILL_FROM_PID_FILE "%BACKEND_PID_FILE%" "backend" "uvicorn;uv run"
call :KILL_FROM_PID_FILE "%WEB_PID_FILE%" "web" "npm run dev;next dev"

rem Verify ports are released (avoid killing unrelated processes).
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ports=@(8000,3000); $deadline=(Get-Date).AddSeconds(20); while ((Get-Date) -lt $deadline) { $allFree=$true; foreach ($port in $ports) { $r = Test-NetConnection -ComputerName localhost -Port $port -WarningAction SilentlyContinue; if ($r -and $r.TcpTestSucceeded) { $allFree=$false; break } }; if ($allFree) { exit 0 }; Start-Sleep -Seconds 1 }; exit 1" >nul
if errorlevel 1 exit /b 1

exit /b 0

:KILL_FROM_PID_FILE
set "PID_FILE=%~1"
set "LABEL=%~2"
set "PATTERNS=%~3"

if not exist "%PID_FILE%" (
  exit /b 0
)

rem Use PowerShell to validate commandline before killing.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='SilentlyContinue'; $pidFile='%PID_FILE%'; $label='%LABEL%'; $patterns = '%PATTERNS%'.Split(';') | Where-Object { $_ -ne '' }; $pidRaw = (Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1); if ($pidRaw) { $pidRaw = $pidRaw.Trim() } else { $pidRaw = '' }; if (-not $pidRaw) { Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue; exit 0 }; if (-not ($pidRaw -match '^[0-9]+$')) { Write-Host ('[' + $label + '] invalid PID in ' + $pidFile + ': ' + $pidRaw); exit 0 }; $targetPid = [int]$pidRaw; $p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $targetPid) -ErrorAction SilentlyContinue; if (-not $p) { Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue; exit 0 }; $cmd = if ($p.CommandLine) { $p.CommandLine.ToLower() } else { '' }; $ok = $false; foreach ($pat in $patterns) { if ($cmd.Contains($pat.ToLower())) { $ok = $true; break } }; if (-not $ok) { Write-Host ('[' + $label + '] PID ' + $targetPid + ' does not look like our process; refusing to kill.'); exit 0 }; & taskkill /PID $targetPid /T /F | Out-Null; Start-Sleep -Milliseconds 200; Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue; exit 0" >nul

exit /b 0

@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "RUN_DIR=%ROOT%.sisyphus\run"
set "BACKEND_PID_FILE=%RUN_DIR%\backend.pid"
set "WEB_PID_FILE=%RUN_DIR%\web.pid"
set "JWT_KEYS_FILE=%RUN_DIR%\jwt-signing-keys.json"

rem Default dev behavior: run Celery tasks inline unless explicitly overridden.
if "%WAYFARER_CELERY_EAGER%"=="" set "WAYFARER_CELERY_EAGER=1"

if not exist "%RUN_DIR%" (
  mkdir "%RUN_DIR%" >nul 2>nul
)

rem Ensure backend dependencies are present before starting.
pushd "%ROOT%backend" >nul
if errorlevel 1 exit /b 1
uv sync
if errorlevel 1 (
  popd >nul
  exit /b 1
)
popd >nul

rem Only install web deps when missing.
if not exist "%ROOT%web\node_modules\" (
  pushd "%ROOT%web" >nul
  if errorlevel 1 exit /b 1
  npm install
  if errorlevel 1 (
    popd >nul
    exit /b 1
  )
  popd >nul
)

rem Start backend (FastAPI) in background.
rem If WAYFARER_JWT_SIGNING_KEYS_JSON is missing, generate a dev key map JSON
rem of the form {"kid":"secret"} and inject env vars into the backend process only.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $runDir='%RUN_DIR%'; $pidFile='%BACKEND_PID_FILE%'; $jwtFile='%JWT_KEYS_FILE%'; $backendCwd='%ROOT%backend'; if (-not $env:WAYFARER_JWT_SIGNING_KEYS_JSON) { New-Item -ItemType Directory -Force -Path $runDir | Out-Null; $kid = if ($env:WAYFARER_JWT_KID_CURRENT) { $env:WAYFARER_JWT_KID_CURRENT } else { 'dev-1' }; $bytes = New-Object byte[] 32; $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($bytes); $rng.Dispose(); $b64 = [Convert]::ToBase64String($bytes).TrimEnd('='); $b64 = $b64.Replace('+','-').Replace('/','_'); $obj = @{}; $obj[$kid] = $b64; $json = ($obj | ConvertTo-Json -Depth 10 -Compress); Set-Content -LiteralPath $jwtFile -Value $json -Encoding UTF8; $env:WAYFARER_JWT_SIGNING_KEYS_JSON = $json; if (-not $env:WAYFARER_JWT_KID_CURRENT) { $env:WAYFARER_JWT_KID_CURRENT = $kid } }; $p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','uv run uvicorn main:app --host :: --port 8000' -WorkingDirectory $backendCwd -WindowStyle Hidden -PassThru; Set-Content -LiteralPath $pidFile -Value $p.Id -Encoding Ascii" >nul
if errorlevel 1 exit /b 1

rem Start web (Next.js) in background.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $pidFile='%WEB_PID_FILE%'; $webCwd='%ROOT%web'; $p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','npm run dev -- -p 3000' -WorkingDirectory $webCwd -WindowStyle Hidden -PassThru; Set-Content -LiteralPath $pidFile -Value $p.Id -Encoding Ascii" >nul
if errorlevel 1 exit /b 1

rem Poll backend health endpoint until ready.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='http://localhost:8000/healthz'; $deadline=(Get-Date).AddSeconds(60); while ((Get-Date) -lt $deadline) { try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $url; if ($r.StatusCode -eq 200) { exit 0 } } catch { }; Start-Sleep -Seconds 1 }; exit 1" >nul
if errorlevel 1 exit /b 1

exit /b 0

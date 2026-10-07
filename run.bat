@echo off
setlocal
cd /d "%~dp0server"

where node >nul 2>&1 || (
    echo Node.js is not installed. Get it from https://nodejs.org/
    pause
    exit /b 1
)

if not exist node_modules (
    echo Installing dependencies...
    call npm install || (pause & exit /b 1)
)

rem First run: generate the two secret codes and keep them in server\.env (gitignored).
if not exist .env (
    node -e "const r=()=>require('crypto').randomBytes(24).toString('base64url');console.log('SENDER_TOKEN='+r());console.log('RECEIVER_TOKEN='+r())" > .env
    echo Created server\.env with new secret codes.
)

for /f "usebackq tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
if not defined PORT set "PORT=8080"

rem WAN access: a Cloudflare tunnel gives a public HTTPS address without port forwarding.
if not exist cloudflared.exe (
    echo Downloading cloudflared...
    curl -fL -o cloudflared.exe https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe || (
        echo Could not download cloudflared.
        pause
        exit /b 1
    )
)

taskkill /im cloudflared.exe /f >nul 2>&1
timeout /t 1 /nobreak >nul
if exist tunnel.log del tunnel.log
start "" /b cloudflared.exe tunnel --no-autoupdate --url http://127.0.0.1:%PORT% --logfile tunnel.log >nul 2>&1

echo Starting tunnel...
set "PUBLIC_URL="
for /l %%i in (1,1,30) do if not defined PUBLIC_URL (
    timeout /t 1 /nobreak >nul
    for /f %%U in ('powershell -NoProfile -Command "if (Test-Path tunnel.log) { (Select-String -Path tunnel.log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1).Matches.Value }"') do set "PUBLIC_URL=%%U"
)

echo.
if defined PUBLIC_URL (
    echo Server address:      %PUBLIC_URL%
) else (
    echo Tunnel did not start. See server\tunnel.log. Server is local only.
)
echo Your code ^(sender^):    %SENDER_TOKEN%
echo His code  ^(receiver^):  %RECEIVER_TOKEN%
echo.

call npm start
taskkill /im cloudflared.exe /f >nul 2>&1
pause

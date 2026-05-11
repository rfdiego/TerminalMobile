@echo off
echo === TerminalMobile Server - Windows Install ===
echo.

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Node.js not found. Download from https://nodejs.org
    pause
    exit /b 1
)

for /f "tokens=1 delims=v" %%i in ('node -v') do set NODERAW=%%i
node -e "const v=process.version.slice(1).split('.')[0]; if(parseInt(v)<18){process.exit(1)}" 2>nul
if %errorlevel% neq 0 (
    echo ERROR: Node.js 18+ required. Current:
    node -v
    pause
    exit /b 1
)

echo Node.js OK:
node -v
echo.

echo Installing dependencies...
call npm install

echo.
echo Installing node-pty (requires Visual Studio Build Tools)...
call npm install node-pty 2>nul
if %errorlevel% neq 0 (
    echo WARNING: node-pty failed. Will use basic mode.
    echo For full PTY support, install VS Build Tools:
    echo https://aka.ms/vs/17/release/vs_BuildTools.exe
    echo Then run: npm install node-pty
)

echo.
if not exist .env (
    copy .env.example .env
    echo Created .env from template.
    echo.
    echo Generate your token:
    call npm run token
    echo.
    echo Copy that token into .env as AUTH_TOKEN=your_token_here
)

echo.
echo === Installation complete! ===
echo Run: start.bat to launch the server
echo.
pause

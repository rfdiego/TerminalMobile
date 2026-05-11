@echo off
title TerminalMobile Server
echo === TerminalMobile Server ===
echo.
if not exist node_modules (
    echo Dependencies not installed. Run install.bat first.
    pause
    exit /b 1
)
if not exist .env (
    echo No .env file. Generating temporary token...
)
node src/index.js
pause

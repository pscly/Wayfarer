@echo off
setlocal

REM Shim for tooling that expects `typescript-language-server` on PATH.
REM Keep it repo-local to avoid global installs.

node "%~dp0web\node_modules\typescript-language-server\lib\cli.mjs" %*

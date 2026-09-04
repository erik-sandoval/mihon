@echo off
REM Double-click wrapper for tools/pull-panel-flags.sh
REM Pulls flagged panel-detection pages from the phone into panel_flags_pulled\
setlocal
set "SCRIPT_DIR=%~dp0"
for %%I in ("bash.exe") do if not "%%~$PATH:I"=="" set "BASH=%%~$PATH:I"
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH (
  echo Could not find Git Bash. Install Git for Windows or run tools/pull-panel-flags.sh directly.
  pause
  exit /b 1
)
"%BASH%" "%SCRIPT_DIR%pull-panel-flags.sh" %*
pause

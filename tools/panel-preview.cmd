@echo off
REM Drag a manga page image (or a folder of them) onto this file, or run:
REM   tools\panel-preview.cmd path\to\page.jpg
REM Opens a 3-up preview: raw ML detections | + content-aware expansion | final zoom stops.
REM
REM Uses uv if available (auto-installs deps into a throwaway env, nothing to set up);
REM otherwise falls back to the system python (needs: pip install numpy pillow ai-edge-litert).

setlocal
set SCRIPT=%~dp0panel-detection-preview.py

where uv >nul 2>&1
if %ERRORLEVEL%==0 (
    uv run "%SCRIPT%" %*
) else (
    python "%SCRIPT%" %*
)

if "%~1"=="" pause
endlocal

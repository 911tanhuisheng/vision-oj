@echo off
setlocal
title Mayue OJ - Vision AI
set "APP_DIR=%~dp0vision-ai"
set "PYTHON_EXE=%APP_DIR%\.venv\Scripts\python.exe"

if not exist "%APP_DIR%\run.py" (
    echo [ERROR] vision-ai\run.py is missing. Keep this BAT in the project root.
    goto failed
)

rem If the correct service is already running, do not start a duplicate.
powershell.exe -NoProfile -Command "try { $r = Invoke-RestMethod 'http://127.0.0.1:8090/health' -TimeoutSec 3; if ($r.status -eq 'ok' -and $r.models) { exit 0 } } catch {} ; exit 1" >nul 2>&1
if not errorlevel 1 (
    echo [READY] Vision AI is already running: http://127.0.0.1:8090/health
    pause
    exit /b 0
)
powershell.exe -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo [ERROR] Port 8090 is in use by another program.
    goto failed
)

if not exist "%PYTHON_EXE%" (
    echo [SETUP] Creating a Python environment...
    where py >nul 2>&1
    if not errorlevel 1 (
        py -3 -m venv "%APP_DIR%\.venv"
    ) else (
        where python >nul 2>&1
        if errorlevel 1 (
            echo [ERROR] Python is missing. Install Python 3.12 or 3.13 first.
            goto failed
        )
        python -m venv "%APP_DIR%\.venv"
    )
    if errorlevel 1 goto failed
)

"%PYTHON_EXE%" -c "import fastapi, uvicorn, ultralytics, easyocr" >nul 2>&1
if errorlevel 1 (
    echo [SETUP] Installing YOLO/OCR dependencies. Keep this window open...
    "%PYTHON_EXE%" -m pip install -r "%APP_DIR%\requirements.txt"
    if errorlevel 1 goto failed
)

pushd "%APP_DIR%"
echo [START] Vision AI health check: http://127.0.0.1:8090/health
echo Keep this window open. Press Ctrl+C to stop the service.
"%PYTHON_EXE%" run.py
set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" (
    echo [ERROR] Vision AI failed or stopped unexpectedly. Check the messages above.
    goto failed
)
echo Vision AI stopped.
pause
exit /b 0

:failed
echo.
echo For ComfyUI image generation, run start-image-ai.bat instead.
pause
exit /b 1

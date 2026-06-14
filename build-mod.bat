@echo off
setlocal

cd /d "%~dp0"

echo Building GUI Auto Clicker mod...
call gradlew.bat build

if errorlevel 1 (
    echo.
    echo Build failed.
    pause
    exit /b 1
)

echo.
echo Build completed successfully.
echo Jar files are in: build\libs
pause

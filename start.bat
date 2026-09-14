@echo off
cd /d "%~dp0"
if exist "composeApp\build\compose\binaries\main\app\YMLStudio\YMLStudio.exe" (
  "composeApp\build\compose\binaries\main\app\YMLStudio\YMLStudio.exe" %*
) else (
  call gradlew.bat :composeApp:run --console=plain
)

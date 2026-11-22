@echo off
REM Compile toutes les sources Java dans bin\ (JDK 11 ou plus recent requis).
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo Java JDK introuvable : installez un JDK 11 ou plus recent, par exemple https://adoptium.net
  exit /b 1
)
echo Compilation...
powershell -NoProfile -Command "javac -encoding UTF-8 -d bin (Get-ChildItem -Recurse src -Filter *.java).FullName; exit $LASTEXITCODE"
if errorlevel 1 (
  echo *** La compilation a echoue ***
  exit /b 1
)
echo Compilation terminee.

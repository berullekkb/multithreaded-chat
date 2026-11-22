@echo off
REM Compile, demarre le serveur (TCP 5000 + web 8080) et ouvre l'interface web.
REM Options possibles : LANCER_SERVEUR.bat --port 5000 --web 8081 --max 50
cd /d "%~dp0"
call COMPILER.bat || (pause & exit /b 1)
start "" cmd /c "timeout /t 2 >nul & start http://localhost:8080"
java -cp bin chat.server.Serveur %*
pause

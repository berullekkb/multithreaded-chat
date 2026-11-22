@echo off
REM Client console. Exemples : LANCER_CLIENT.bat    ou    LANCER_CLIENT.bat 192.168.1.20 5000
cd /d "%~dp0"
if not exist bin\chat\client\Client.class (
  call COMPILER.bat || (pause & exit /b 1)
)
java -cp bin chat.client.Client %*
pause

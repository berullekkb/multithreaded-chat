@echo off
REM Envoie les commits locaux sur GitHub (berullekkb/multithreaded-chat).
REM Le workflow GitHub Actions teste le projet puis publie le site docs/ sur GitHub Pages.
cd /d "%~dp0"
git push -u origin main
if errorlevel 1 (echo *** Le push a echoue *** & pause & exit /b 1)
echo Publie sur https://github.com/berullekkb/multithreaded-chat
echo Site : https://berullekkb.github.io/multithreaded-chat/
timeout /t 5 >nul

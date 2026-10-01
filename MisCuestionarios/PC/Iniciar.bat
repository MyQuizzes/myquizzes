@echo off
cd /d "%~dp0"
py --version >nul 2>nul
if %errorlevel%==0 (py servidor.py & goto fin)
python --version >nul 2>nul
if %errorlevel%==0 (python servidor.py & goto fin)
echo Falta instalar Python: https://www.python.org/downloads/  (marca "Add Python to PATH")
:fin
pause

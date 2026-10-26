@echo off
cd /d "%~dp0"
python -X utf8 scripts\manage.py stop
if errorlevel 1 pause

@echo off
cd /d "%~dp0"
python -X utf8 scripts\manage.py start
if errorlevel 1 pause

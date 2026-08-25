@echo off
rem ============================================================
rem  LumiPOS - MySQL mode (shop server / multi-terminal)
rem  Requires: MySQL running with the `lumipos` database and
rem  user `lumi` (see application-mysql.yml for one-time setup).
rem ============================================================
title LumiPOS Server (MySQL)
cd /d "%~dp0bms-backend\target"
java -Dspring.profiles.active=mysql -Dserver.port=17234 -jar bms-backend-1.0.0.jar
pause

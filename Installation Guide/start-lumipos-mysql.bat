@echo off
rem ============================================================
rem  LumiPOS - Start the app in MySQL (shop server) mode
rem  ------------------------------------------------------------
rem  Runs the backend Spring Boot server with the MySQL profile.
rem  Requires that Steps 1-3 have been completed successfully
rem  (MySQL running + 'lumipos' database + 'lumi' user + .env set).
rem ============================================================
setlocal
title LumiPOS Server (MySQL)
color 0A

cd /d "%~dp0..\bms-backend\target"

if not exist "bms-backend-1.0.0.jar" (
    color 0C
    echo  [ERROR] bms-backend-1.0.0.jar not found.
    echo          Expected in: bms-backend\target\
    echo.
    pause
    exit /b 1
)

echo  Starting LumiPOS backend (MySQL mode) on port 17234...
echo  Keep this window open while using the shop.
echo.
java -Dspring.profiles.active=mysql -Dserver.port=17234 -jar bms-backend-1.0.0.jar

echo.
echo  LumiPOS server stopped.
pause
endlocal

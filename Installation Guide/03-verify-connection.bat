@echo off
rem ============================================================
rem  LumiPOS - Step 3 : Verify connection
rem  ------------------------------------------------------------
rem  Tests that LumiPOS's own account (lumi) can connect to the
rem  'lumipos' database on this computer. It reads the password
rem  from .env (MYSQL_PASSWORD) so no credentials are typed here.
rem ============================================================
setlocal EnableDelayedExpansion
title LumiPOS - Step 3 of 3 : Verify Connection
color 0D

echo.
echo  ==========================================
echo   LumiPOS Installation - Step 3 of 3
echo   Verifying the lumi / lumipos connection
echo  ==========================================
echo.

rem ---- Locate the mysql client ---------------------------------
set "MYSQL_BIN="
for %%D in ("%ProgramFiles%\MySQL\MySQL Server 8.0\bin\mysql.exe" ^
            "%ProgramFiles%\MySQL\MySQL Server 8.4\bin\mysql.exe" ^
            "%ProgramFiles%\MySQL\MySQL Server 8.1\bin\mysql.exe" ^
            "%ProgramFiles(x86)%\MySQL\MySQL Server 8.0\bin\mysql.exe" ^
            "C:\xampp\mysql\bin\mysql.exe") do (
    if not defined MYSQL_BIN if exist "%%~D" set "MYSQL_BIN=%%~D"
)
if not defined MYSQL_BIN (
    for /f "delims=" %%X in ('where mysql 2^>nul') do (
        if not defined MYSQL_BIN set "MYSQL_BIN=%%X"
    )
)
if not defined MYSQL_BIN (
    color 0C
    echo  [ERROR] mysql.exe not found. Run Step 1 first.
    pause
    exit /b 1
)

rem ---- Read MYSQL_PASSWORD from .env ----------------------------
set "TARGET_ENV=%~dp0..\.env"
if not exist "%TARGET_ENV%" (
    color 0C
    echo  [ERROR] .env not found at %TARGET_ENV%
    echo          Run Step 2 first.
    pause
    exit /b 1
)

set "LUMI_PW="
for /f "usebackq delims=" %%L in ("%TARGET_ENV%") do (
    echo %%L | findstr /I "^MYSQL_PASSWORD=" >nul && set "LUMI_PW=%%L"
)
if not defined LUMI_PW (
    color 0C
    echo  [ERROR] MYSQL_PASSWORD is not set in .env
    echo          Run Step 2 first.
    pause
    exit /b 1
)
rem Keep only the part after the '=' (strip the MYSQL_PASSWORD= prefix).
set "LUMI_PW=%LUMI_PW:*MYSQL_PASSWORD=%"
set "LUMI_PW=%LUMI_PW:~1%"
rem Remove any trailing CR/LF residue from the .env read.
for /f "delims=" %%V in ("%LUMI_PW%") do set "LUMI_PW=%%V"

rem ---- Attempt a real connection --------------------------------
echo  Connecting as: lumi  ->  database: lumipos
echo.
"%MYSQL_BIN%" -h 127.0.0.1 -P 3306 -u lumi -p"!LUMI_PW!" --connect-timeout=15 lumipos -e "SELECT DATABASE() AS connected_to;" 2> "%~dp0_verify_err.log"
if errorlevel 1 (
    color 0C
    echo  [ERROR] Connection FAILED.
    echo  Detail:
    type "%~dp0_verify_err.log"
    echo.
    echo  Possible fixes:
    echo    - Wrong password in .env - re-run Step 2.
    echo    - MySQL not running - run Step 1.
    echo    - User 'lumi' was not created - run Step 2.
    echo.
    del "%~dp0_verify_err.log" >nul 2>&1
    pause
    exit /b 1
)

del "%~dp0_verify_err.log" >nul 2>&1
echo.
echo  ==========================================
echo   RESULT: SUCCESS!
echo   You are now ready to start LumiPOS.
echo.
echo   To run the app, double-click:
echo       start-lumipos-mysql.bat   (in this folder)
echo  ==========================================
echo.
pause
endlocal
exit /b 0

@echo off
rem ============================================================
rem  LumiPOS - Step 1 : MySQL check
rem  ------------------------------------------------------------
rem  Checks that MySQL Server is installed and running.
rem  If it is NOT installed yet, follow README.md first to
rem  download and install "MySQL Community Server" (and optionally
rem  MySQL Workbench for browsing the database).
rem ============================================================
setlocal EnableDelayedExpansion
title LumiPOS - Step 1 of 3 : MySQL Check
color 0A

echo.
echo  ==========================================
echo   LumiPOS Installation - Step 1 of 3
echo   Checking for MySQL Server
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
    echo  [ERROR] The MySQL client was NOT found.
    echo.
    echo   MySQL Server does not appear to be installed, or it is
    echo   not in a standard location.
    echo.
    echo   Please install "MySQL Community Server 8.0" first.
    echo   See README.md in this folder for download instructions.
    echo.
    pause
    exit /b 1
)

echo  [OK] MySQL client found:
echo       !MYSQL_BIN!
echo.

rem ---- Find a running MySQL Windows service ---------------------
set "SVC_NAME="
for %%S in (MySQL80 MySQL MySQL84 MySQL57) do (
    if not defined SVC_NAME (
        sc query "%%S" 2>nul | findstr /I "RUNNING" >nul && set "SVC_NAME=%%S"
    )
)

if defined SVC_NAME (
    echo  [OK] MySQL service "!SVC_NAME!" is RUNNING.
) else (
    color 0E
    echo  [WARN] No running MySQL Windows service was detected by common name.
    echo         If MySQL is installed under a different service name, please
    echo         start it manually: Win+R -^> services.msc, find MySQL, click Start.
    echo.
    echo         Continuing with a port check below...
)

rem ---- Final quick TCP check on port 3306 -------------------------
echo.
echo  Checking that MySQL is listening on port 3306...
netstat -an | findstr ":3306" | findstr "LISTENING" >nul
if not errorlevel 1 (
    echo  [OK] MySQL is listening on port 3306.
) else (
    color 0C
    echo  [ERROR] Nothing is listening on port 3306.
    echo          MySQL may not be running yet. Please start the
    echo          MySQL service, then run this script again.
    echo.
    pause
    exit /b 1
)

echo.
echo  ==========================================
echo   RESULT: MySQL is installed and running.
echo   You can now run Step 2 : 02-setup-database.bat
echo  ==========================================
echo.
pause
endlocal
exit /b 0

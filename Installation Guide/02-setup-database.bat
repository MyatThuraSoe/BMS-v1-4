@echo off
rem ============================================================
rem  LumiPOS - Step 2 : Database + user setup (all-in-one)
rem  ------------------------------------------------------------
rem  Connects to MySQL as root and, using the credentials you
rem  enter, performs the one-time setup that LumiPOS needs:
rem    1. Creates the database     : lumipos
rem    2. Creates the MySQL user   : lumi@localhost
rem    3. Grants ALL privileges    : on lumipos.*
rem    4. Flushes privileges
rem    5. Writes MYSQL_PASSWORD into .env (needed by the app)
rem  ------------------------------------------------------------
rem  IMPORTANT: You must know the MySQL "root" password.
rem  It is the password you chose when you installed MySQL.
rem  It is asked here at run-time and is NEVER stored anywhere.
rem ============================================================
setlocal EnableDelayedExpansion
title LumiPOS - Step 2 of 3 : Database Setup
color 0B

echo.
echo  ==========================================
echo   LumiPOS Installation - Step 2 of 3
echo   Database and user creation
echo  ==========================================
echo.
echo  This step connects to your MySQL server as the "root"
echo  user and creates everything LumiPOS needs.
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
    echo  [ERROR] mysql.exe not found. Run 01-check-mysql.bat first.
    echo.
    pause
    exit /b 1
)
echo  [OK] Using MySQL client:
echo       !MYSQL_BIN!
echo.

rem ---- Ask for the MySQL root password ---------------------------
set "ROOT_PW="
set /p "ROOT_PW=Enter the MySQL [root] password (what you set during MySQL install): "

rem ---- Ask for the LumiPOS 'lumi' user password ------------------
echo.
echo  Now choose a password for LumiPOS's own database account
echo  (username will be: lumi). This is the password stored in .env
echo  and used by the app to connect. Do NOT forget it.
echo.
set "LUMI_PW="
set /p "LUMI_PW=Enter a new password for the 'lumi' user: "

if "%LUMI_PW%"=="" (
    color 0C
    echo  [ERROR] Password cannot be empty.
    pause
    exit /b 1
)

echo.
echo  Creating everything. Please wait...

rem ---- Run the setup SQL via root --------------------------------
"%MYSQL_BIN%" -u root -p"!ROOT_PW!" --connect-timeout=15 -e ^
  "CREATE DATABASE IF NOT EXISTS lumipos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" ^
  >nul 2> "%~dp0_setup_root_err.log"
if errorlevel 1 (
    color 0C
    echo.
    echo  [ERROR] Could not connect as MySQL root.
    echo          Possible reasons:
    echo            1. Wrong root password
    echo            2. MySQL service is not running. Run Step 1 first.
    echo            3. MySQL is not installed. Run Step 1 first.
    echo.
    echo  Detail:
    type "%~dp0_setup_root_err.log"
    echo.
    pause
    exit /b 1
)

rem ---- Build a temp SQL file with the user + grants --------------
rem  (Keeps the password out of the command line where possible.)
set "SQL_FILE=%~dp0_setup_lumipos.sql"
(
    echo CREATE USER IF NOT EXISTS 'lumi'@'localhost' IDENTIFIED BY '!LUMI_PW!';
    echo ALTER USER 'lumi'@'localhost' IDENTIFIED BY '!LUMI_PW!';
    echo GRANT ALL PRIVILEGES ON lumipos.* TO 'lumi'@'localhost';
    echo FLUSH PRIVILEGES;
) > "%SQL_FILE%"

"%MYSQL_BIN%" -u root -p"!ROOT_PW!" --connect-timeout=15 < "%SQL_FILE%" >nul 2> "%~dp0_setup_user_err.log"
set "ERR=%errorlevel%"
del "%SQL_FILE%" >nul 2>&1
if not "%ERR%"=="0" (
    color 0C
    echo.
    echo  [ERROR] Failed to create the 'lumi' user / privileges.
    echo  Detail:
    type "%~dp0_setup_user_err.log"
    echo.
    pause
    exit /b 1
)

rem ---- Write MYSQL_PASSWORD into .env -----------------------------
rem  This folder is inside the project, so .env is one level up.
echo.
echo  Updating .env with MYSQL_USERNAME and MYSQL_PASSWORD...
set "ROOT_DIR=%~dp0.."
set "TARGET_ENV=%ROOT_DIR%\.env"

if not exist "%TARGET_ENV%" (
    copy /Y "%ROOT_DIR%\.env.example" "%TARGET_ENV%" >nul 2>&1
)
if not exist "%TARGET_ENV%" (
    color 0C
    echo  [ERROR] Could not find or create .env at: %TARGET_ENV%
    echo          Please run: copy ".env.example" ".env"  in the project folder.
    pause
    exit /b 1
)

rem Update MYSQL_USERNAME (ensure it is lumi)
findstr /I "^MYSQL_USERNAME=" "%TARGET_ENV%" >nul 2>&1
if errorlevel 1 (
    echo MYSQL_USERNAME=lumi>> "%TARGET_ENV%"
) else (
    powershell -NoProfile -Command "(Get-Content -LiteralPath '%TARGET_ENV%') -replace '(?i)^MYSQL_USERNAME=.*', 'MYSQL_USERNAME=lumi' | Set-Content -LiteralPath '%TARGET_ENV%'"
)

rem Update MYSQL_PASSWORD (validate non-empty before)
if "%LUMI_PW%"=="" set "LUMI_PW=###"
rem Sanitise any double quotes out of the password before writing.
set "LUMI_PW=%LUMI_PW:"=%"
if not defined LUMI_PW set "LUMI_PW=###"
powershell -NoProfile -Command "$f='%TARGET_ENV%'; $c=Get-Content $f; $found=$false; $c=$c | ForEach-Object { if ($_ -match '(?i)^MYSQL_PASSWORD=') { $found=$true; 'MYSQL_PASSWORD=%LUMI_PW%' } else { $_ } }; if (-not $found) { $c += 'MYSQL_PASSWORD=%LUMI_PW%' }; Set-Content -LiteralPath $f -Value $c"

echo  [OK] .env updated.
del "%~dp0_setup_root_err.log" >nul 2>&1
del "%~dp0_setup_user_err.log" >nul 2>&1

echo.
echo  ==========================================
echo   RESULT: Database setup complete!
echo     - Database : lumipos          (created)
echo     - User     : lumi@localhost   (created)
echo     - Privileges: ALL on lumipos.* (granted)
echo     - .env     : MYSQL_PASSWORD set
echo.
echo   Next: run Step 3 : 03-verify-connection.bat
echo  ==========================================
echo.
pause
endlocal
exit /b 0

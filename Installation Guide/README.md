# LumiPOS Installation Guide

This guide takes you from a fresh computer to a running LumiPOS shop. You do **not** need to know how to use MySQL — the included scripts do everything for you step by step.

All the scripts in this folder were written for **Windows**. Everything is automated: creating the database, creating the app's database account, granting permissions, and writing your password into the app's config file.

---

## What you need (one-time)

1. **Java** (to run the backend). The LumiPOS folder usually includes a bundled Java runtime, but if not, install Java 17+.
2. **MySQL Server 8.0** (the database engine). MySQL Workbench is optional — it is only a visual tool for peeking at the data; you do **not** need it to use LumiPOS.
3. The LumiPOS application files (this project).

---

## Part A — Install MySQL (only if you don't have it yet)

1. Go to the MySQL Community downloads:
   - **MySQL Community Server 8.0**: <https://dev.mysql.com/downloads/mysql/>
     (choose the Windows `.msi` installer — usually named `mysql-installer-community-8.0.x.msi`)
2. Run the installer and choose **"Server only"** (or "Full" if you also want Workbench).
3. During installation it will ask for a **root password**. **Write this password down** — you will need it in Step 2.
4. It will install and start MySQL as a Windows service automatically, usually named **MySQL80**.

> Optional but handy: also install **MySQL Workbench** from <https://dev.mysql.com/downloads/workbench/> if you ever want to open the database visually and look at tables. It is not required for LumiPOS to run.

### How to confirm MySQL installed
- Open the Windows **Services** app (`Win+R` → type `services.msc` → Enter).
- Look for a service named **MySQL** or **MySQL80** and check its status is **Running**.

---

## Part B — Run the LumiPOS setup scripts (3 quick steps)

Open the **`Installation Guide`** folder that came with this project and double-click these files **in order**.

### Step 1 — `01-check-mysql.bat`
Checks that MySQL is installed and running on this computer.
- If everything is fine it will say **RESULT: MySQL is installed and running.**
- If it reports MySQL is missing, go back to **Part A** and install it first.

### Step 2 — `02-setup-database.bat`  🔑 (this is the important one)
This creates everything LumiPOS needs. It will ask you for **two** passwords:

1. **MySQL root password** — the one you set during MySQL installation.
2. **A new password for the `lumi` user** — this is LumiPOS's own database account. Pick any password you like (e.g. `LumiPOS26`). You will see it typed on screen; that is expected. The script writes this password into the app's `.env` file so the app can connect.

After it finishes you will see:
```
RESULT: Database setup complete!
  - Database   : lumipos         (created)
  - User       : lumi@localhost  (created)
  - Privileges : ALL on lumipos.* (granted)
  - .env       : MYSQL_PASSWORD set
```

> What it actually did (so you understand it):
> - Created a database named `lumipos` (with `utf8mb4` character set).
> - Created a MySQL user `lumi@localhost`.
> - Gave that user **all privileges** on the `lumipos` database only (not the whole server).
> - Saved the password into the project's `.env` file.

### Step 3 — `03-verify-connection.bat`
Tests that the `lumi` user can actually connect to the `lumipos` database using the password written to `.env`. You should see **RESULT: SUCCESS!**

If it fails, the most common cause is a typo in the `lumi` password — simply run **Step 2** again and re-enter it.

---

## Part C — Start LumiPOS

Double-click **`start-lumipos-mysql.bat`** in this folder. A black window will open and start the LumiPOS server on port `17234`. **Keep that window open while you use the shop.**

Then open your browser (or the LumiPOS app) and go to:

```
http://localhost:17234
```

The very first time it starts, LumiPOS creates its tables automatically inside the `lumipos` database — no extra steps needed. After that, other phones/tablets on the same shop Wi-Fi can connect too (multi-terminal mode).

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Step 1 says MySQL not found | Install MySQL (Part A), then re-run Step 1. |
| Step 2 says "Could not connect as MySQL root" | Wrong root password, or MySQL isn't running. Re-check root password and that the MySQL service is **Running**. |
| Step 3 fails | Re-run Step 2 to set the `lumi` password again, then re-run Step 3. |
| "Port 17234 is already in use" | Another LumiPOS instance is already running. Close it first, or use the existing one. |
| I forgot my `lumi` password | No problem — run **Step 2** again and set a new one. |
| Java not installed error | Install Java 17 or newer, or use the bundled Java runtime that ships with LumiPOS. |

---

## What the scripts do not touch

- The `.env` file contains your `lumi` password and Google/OAuth keys. It is **ignored by Git** and should never be shared or committed.
- No real passwords are ever written to disk by the scripts except into the correct `.env` line (`MYSQL_PASSWORD=`). The root password you type is used only at run-time and then discarded.

---

## Files in this folder

| File | Purpose |
|---|---|
| `01-check-mysql.bat` | Verify MySQL is installed and running (Step 1). |
| `02-setup-database.bat` | Create `lumipos` DB, `lumi` user, privileges, update `.env` (Step 2). |
| `03-verify-connection.bat` | Test the `lumi` connection (Step 3). |
| `start-lumipos-mysql.bat` | Start LumiPOS in MySQL (shop server) mode. |

package com.example.lumipos.util

import android.content.Context
import android.content.pm.PackageManager
import android.view.Window
import android.view.WindowManager
import java.io.File

/**
 * Basic tamper checks for an offline device that holds financial records. Not a security
 * boundary - a determined attacker with physical access can defeat every one of these - but it
 * surfaces the obvious cases (Magisk, su binaries, test builds) so the shop knows the device is
 * compromised.
 */
object SecurityUtils {

    private val SU_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon"
    )

    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine"
    )

    /** True when a su binary, a root manager app or a test-keys build is present. */
    fun isRooted(context: Context): Boolean {
        if (hasSuBinary()) return true
        if (android.os.Build.TAGS?.contains("test-keys") == true) return true
        return hasRootPackage(context)
    }

    /** Turns the screenshot/recording block (FLAG_SECURE) on or off for [window]. */
    fun applyScreenshotBlock(window: Window, blocked: Boolean) {
        if (blocked) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun hasSuBinary(): Boolean = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun hasRootPackage(context: Context): Boolean = ROOT_PACKAGES.any { pkg ->
        runCatching {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrElse { e ->
            // getPackageInfo throws NameNotFoundException for the normal (not installed) case.
            e !is PackageManager.NameNotFoundException
        }
    }
}

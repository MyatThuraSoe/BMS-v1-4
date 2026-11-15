package com.example.lumipos.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.lumipos.data.local.AppDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the Room database (Phase 6, SQLCipher).
 *
 * A random 32-byte passphrase is generated on first run and wrapped with an AES/GCM key that
 * lives in the Android Keystore - the wrapped copy goes into SharedPreferences, the raw key never
 * leaves the Keystore. On upgrade from an earlier (plaintext) install the existing database is
 * converted in place with `sqlcipher_export`.
 */
object DatabaseEncryption {

    private const val PREFS = "lumipos_secure"
    private const val PREF_IV = "db_key_iv"
    private const val PREF_CT = "db_key_ct"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "lumipos_db_key"
    private const val PLAINTEXT_HEADER = "SQLite format 3\u0000"

    /** The passphrase Room should open the database with, creating/wrapping one if needed. */
    fun getOrCreatePassphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString(PREF_IV, null)
        val ct = prefs.getString(PREF_CT, null)
        if (iv != null && ct != null) {
            return decrypt(iv, ct)
        }
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encodeToString(random, Base64.NO_WRAP)
        val (newIv, newCt) = encrypt(passphrase)
        prefs.edit().putString(PREF_IV, newIv).putString(PREF_CT, newCt).apply()
        return passphrase
    }

    /**
     * Converts a pre-encryption (plaintext) database to SQLCipher format on first launch after
     * the upgrade. No-op when the file is already encrypted or does not exist yet.
     */
    fun migrateIfNeeded(context: Context) {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!dbFile.exists() || !isPlaintext(dbFile)) return

        val passphrase = getOrCreatePassphrase(context)
        val tmp = File(dbFile.parentFile, AppDatabase.DB_NAME + ".enc_tmp")
        if (tmp.exists()) tmp.delete()

        val source = SQLiteDatabase.openOrCreateDatabase(dbFile, "", null, null, null)
        try {
            // Fold the WAL into the main file before copying rows across.
            source.rawExecSQL("PRAGMA wal_checkpoint(FULL)")
            val escapedPath = tmp.absolutePath.replace("'", "''")
            val escapedKey = passphrase.replace("'", "''")
            source.rawExecSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedKey'")
            source.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            source.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            source.close()
        }

        // Swap the encrypted copy in, dropping the stale plaintext journal files.
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
        if (!dbFile.delete() || !tmp.renameTo(dbFile)) {
            throw IllegalStateException("Could not replace plaintext database with encrypted copy")
        }
    }

    /** True when the file starts with the well-known plaintext SQLite header. */
    private fun isPlaintext(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(16)
                stream.read(header) == 16 && String(header, Charsets.ISO_8859_1) == PLAINTEXT_HEADER
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(ivB64: String, ctB64: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

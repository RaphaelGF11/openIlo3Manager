package net.raphaelgf11.ilo3manager.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Remembers the backup secret on this device so routine synchronisation does not ask for it.
 *
 * Held in [EncryptedSharedPreferences], whose master key lives in the Android Keystore: the file
 * sits in the app's private storage, unreadable by other apps, and its contents are encrypted with
 * a key the hardware will not hand out — so even with root the stored value is not directly
 * usable. It is a convenience cache only: the authoritative copy of the secret is the one in the
 * user's head, and it is what makes a backup restorable on a different device.
 */
class BackupSecretStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ilo3manager_backup",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var secret: String?
        get() = prefs.getString(KEY_SECRET, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) remove(KEY_SECRET) else putString(KEY_SECRET, value)
            }.apply()
        }

    val hasSecret: Boolean get() = !secret.isNullOrEmpty()

    /** Last successful synchronisation, epoch milliseconds, or 0. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    fun forget() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_SECRET = "secret"
        const val KEY_LAST_SYNC = "last_sync_at"
    }
}

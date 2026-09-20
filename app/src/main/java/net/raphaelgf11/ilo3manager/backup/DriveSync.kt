package net.raphaelgf11.ilo3manager.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import java.io.IOException

/** Scope for the private application data folder — it grants no access to the user's own files. */
private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

/**
 * Backs the app's configuration up to the signed-in Google account and restores it.
 *
 * Only the application data folder is touched, so the app can neither see nor alter anything else
 * in the user's Drive — and the backup itself is encrypted before it leaves the device.
 */
object DriveSync {

    fun signInClient(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
                .build(),
        )

    fun signedInAccount(context: Context): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    val driveScope: Scope get() = Scope(DRIVE_APPDATA_SCOPE)

    /**
     * Whether the account actually granted access to the application data folder.
     *
     * Signing in and granting the scope are two separate things: an account can be linked with the
     * consent dialog dismissed or previously declined, leaving the app with an account it cannot
     * use. Checking this is what turns a silent no-op into something the user can act on.
     */
    fun hasDriveConsent(context: Context): Boolean {
        val account = signedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, driveScope)
    }

    /** Forgets the account on this device. The backup already in Drive is left untouched. */
    fun signOut(context: Context, onDone: () -> Unit) {
        signInClient(context).signOut().addOnCompleteListener { onDone() }
    }

    /** Also revokes the app's access on the Google side, so the next sign-in asks for consent again. */
    fun revokeAccess(context: Context, onDone: () -> Unit) {
        signInClient(context).revokeAccess().addOnCompleteListener { onDone() }
    }

    /**
     * Blocking: fetches an OAuth token for the account. Must not run on the main thread — it can
     * hit the network, and on first use it shows the consent dialog.
     */
    private fun tokenFor(context: Context, account: Account): String =
        GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_APPDATA_SCOPE")

    /** Encrypts the current configuration and uploads it, replacing any previous backup. */
    fun backup(context: Context, repository: HostRepository, secret: String) {
        val account = signedInAccount(context)?.account
            ?: throw IOException("Aucun compte Google connecté.")
        val payload = BackupPayload.serialize(repository.getHosts())
        val sealed = BackupCrypto.encrypt(payload, secret)
        DriveAppDataClient(tokenFor(context, account)).upload(sealed)
    }

    /**
     * Downloads and decrypts the backup. Returns the hosts it contains without writing anything,
     * so the caller can confirm before replacing what is on the device.
     */
    fun fetchBackup(context: Context, secret: String): List<SshHost> {
        val account = signedInAccount(context)?.account
            ?: throw IOException("Aucun compte Google connecté.")
        val sealed = DriveAppDataClient(tokenFor(context, account)).download()
            ?: throw IOException("Aucune sauvegarde trouvée sur ce compte.")
        return BackupPayload.deserialize(BackupCrypto.decrypt(sealed, secret))
    }

    fun deleteBackup(context: Context) {
        val account = signedInAccount(context)?.account ?: return
        DriveAppDataClient(tokenFor(context, account)).deleteBackup()
    }
}

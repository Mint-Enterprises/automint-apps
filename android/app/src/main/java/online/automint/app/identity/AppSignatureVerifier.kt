package online.automint.app.identity

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import online.automint.app.BuildConfig
import online.automint.app.R
import java.security.MessageDigest

class AppSignatureVerifier(private val activity: Activity) {

    fun verify() {
        if (BuildConfig.IS_DEV) return
        val expected = BuildConfig.EXPECTED_SIGNER_SHA256
        if (expected.isBlank()) return

        val actual = runCatching { currentSignerSha256() }.getOrNull() ?: return
        if (!actual.equals(expected, ignoreCase = true)) {
            showTamperWarning()
        }
    }

    private fun currentSignerSha256(): String? {
        val pm = activity.packageManager
        val pkg = activity.packageName
        @Suppress("DEPRECATION")
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }

        val sig = signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun showTamperWarning() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.integrity_warning_title)
            .setMessage(R.string.integrity_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.integrity_warning_download) { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DOWNLOAD_URL)),
                    )
                }
                activity.finish()
            }
            .setNegativeButton(R.string.integrity_warning_continue, null)
            .show()
    }
}

package online.automint.app.settings

import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import online.automint.app.BuildConfig
import online.automint.app.R
import online.automint.app.consent.ConsentManager
import online.automint.app.security.BiometricGate
import online.automint.app.telemetry.TelemetryClient

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("pref_about_version")?.summary = BuildConfig.VERSION_NAME

        val biometricPref = findPreference<SwitchPreferenceCompat>(SettingsStore.KEY_BIOMETRIC_LOCK)
        biometricPref?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val turningOn = newValue as Boolean
                if (!turningOn) return@OnPreferenceChangeListener true

                val ctx = context ?: return@OnPreferenceChangeListener false
                val canUse = BiometricManager.from(ctx)
                    .canAuthenticate(BiometricGate.AUTHENTICATORS)
                if (canUse != BiometricManager.BIOMETRIC_SUCCESS) {
                    Toast.makeText(ctx, R.string.settings_biometric_unavailable, Toast.LENGTH_LONG).show()
                    return@OnPreferenceChangeListener false
                }
                BiometricGate.prompt(
                    activity = requireActivity(),
                    title = getString(R.string.biometric_confirm_enable_title),
                    subtitle = getString(R.string.biometric_confirm_enable_subtitle),
                    onSuccess = {
                        biometricPref.isChecked = true
                    },
                    onFailure = {
                        biometricPref.isChecked = false
                    },
                )
                false
            }

        val telemetryPref = findPreference<SwitchPreferenceCompat>(ConsentManager.KEY_TELEMETRY_CONSENT)
        telemetryPref?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                TelemetryClient.getInstance(requireContext()).setConsent(newValue as Boolean)
                true
            }

        val fraudPref = findPreference<SwitchPreferenceCompat>(ConsentManager.KEY_FRAUD_SIGNALS)
        fraudPref?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val participating = newValue as Boolean
                val consentManager = ConsentManager(requireContext())
                if (!participating) {
                    CookieManager.getInstance().apply {
                        setCookie(
                            BuildConfig.TARGET_URL,
                            "_amfp_did=; Path=/; Secure; SameSite=Lax; Max-Age=0",
                        )
                        flush()
                    }
                    consentManager.setFingerprintObjection(true)
                } else {
                    consentManager.setFingerprintObjection(false)
                }
                true
            }
    }
}

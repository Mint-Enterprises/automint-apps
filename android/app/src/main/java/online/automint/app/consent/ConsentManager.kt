package online.automint.app.consent

import android.content.Context
import android.telephony.TelephonyManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.Locale

class ConsentManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    var disclosureShown: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_SHOWN, DEFAULT_DISCLOSURE_SHOWN)
        set(value) = prefs.edit { putBoolean(KEY_DISCLOSURE_SHOWN, value) }

    val telemetryConsent: Boolean
        get() = prefs.getBoolean(KEY_TELEMETRY_CONSENT, DEFAULT_TELEMETRY_CONSENT)

    var fraudSignalsEnabled: Boolean
        get() = prefs.getBoolean(KEY_FRAUD_SIGNALS, DEFAULT_FRAUD_SIGNALS)
        set(value) = prefs.edit { putBoolean(KEY_FRAUD_SIGNALS, value) }

    val fingerprintObjection: Boolean
        get() = !fraudSignalsEnabled

    fun fingerprintAllowed(): Boolean = !fingerprintObjection

    fun telemetryAllowed(): Boolean =
        if (isOptInRegime()) telemetryConsent else telemetryConsent || !hasTelemetryChoice()

    fun needsDisclosure(): Boolean = !disclosureShown

    fun defaultTelemetryConsent(): Boolean = !isOptInRegime()

    fun markDisclosureShown() {
        disclosureShown = true
    }

    fun setTelemetryConsent(value: Boolean) {
        prefs.edit {
            putBoolean(KEY_TELEMETRY_CONSENT, value)
            putBoolean(KEY_TELEMETRY_CHOICE_MADE, true)
        }
    }

    fun setFingerprintObjection(value: Boolean) {
        fraudSignalsEnabled = !value
    }

    fun isEuLike(context: Context = appContext): Boolean {
        val sim = simCountry(context)
        if (sim != null) return sim in EU_LIKE_COUNTRIES
        val locale = localeCountry()
        if (locale != null) return locale in EU_LIKE_COUNTRIES
        return true
    }

    fun isOptInRegime(context: Context = appContext): Boolean =
        universalOptIn || isEuLike(context)

    private fun simCountry(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return tm.simCountryIso?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    private fun localeCountry(): String? =
        Locale.getDefault().country.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() }

    private fun hasTelemetryChoice(): Boolean =
        prefs.getBoolean(KEY_TELEMETRY_CHOICE_MADE, false)

    companion object {
        const val universalOptIn: Boolean = true

        const val KEY_DISCLOSURE_SHOWN = "consent_disclosure_shown"
        const val KEY_TELEMETRY_CONSENT = "consent_telemetry"
        const val KEY_TELEMETRY_CHOICE_MADE = "consent_telemetry_choice_made"
        const val KEY_FRAUD_SIGNALS = "consent_fraud_signals"

        const val DEFAULT_DISCLOSURE_SHOWN = false
        const val DEFAULT_TELEMETRY_CONSENT = false
        const val DEFAULT_FRAUD_SIGNALS = true

        private val EU_LIKE_COUNTRIES: Set<String> = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK",
            "SI", "ES", "SE",
            "IS", "LI", "NO", "GB",
        )
    }
}

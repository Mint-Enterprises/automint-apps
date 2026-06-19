package online.automint.app.web

import online.automint.app.BuildConfig

object AppUserAgent {
    const val TOKEN = "AutomintApp"
    val suffix: String = "$TOKEN/${BuildConfig.VERSION_NAME} (Android)"
}

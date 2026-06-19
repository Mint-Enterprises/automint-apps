package online.automint.app.update

import android.app.Activity

interface UpdateStrategy {
    fun check(activity: Activity)
    fun onDestroy() {}
}

interface UpdateUi {
    fun showUpdateAvailable(versionName: String, onAccept: () -> Unit)
    fun hideUpdateAvailable()
    fun showUpdateRequired(versionName: String, onAccept: () -> Unit)
}

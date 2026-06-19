package online.automint.app.identity

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import online.automint.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class DeviceIdentity private constructor(
    private val appContext: Context,
    val deviceId: String,
    val installId: String,
) {

    fun fingerprintJson(): String = buildSignalJson(includeIdentity = true).toString()

    fun envJson(): String = buildSignalJson(includeIdentity = false).toString()

    private fun buildSignalJson(includeIdentity: Boolean): JSONObject {
        val json = JSONObject()
        if (includeIdentity) {
            json.put("deviceId", deviceId)
            json.put("installId", installId)
        }
        json.put("platform", "android")
        json.putOpt("manufacturer", safe { Build.MANUFACTURER })
        json.putOpt("model", safe { Build.MODEL })
        json.putOpt("osVersion", safe { Build.VERSION.RELEASE })
        json.putOpt("sdkInt", safe { Build.VERSION.SDK_INT })
        json.putOpt("arch", safe { Build.SUPPORTED_ABIS.firstOrNull() })
        json.putOpt("cpuCount", safe { Runtime.getRuntime().availableProcessors() })
        json.putOpt("totalMemMb", safe { totalMemMb() })
        json.putOpt("locale", safe { Locale.getDefault().toLanguageTag() })
        json.putOpt("timezone", safe { TimeZone.getDefault().id })
        json.putOpt("screen", safe { screenJson() })
        json.putOpt("appVersion", safe { BuildConfig.VERSION_NAME })
        json.putOpt("appVersionCode", safe { BuildConfig.VERSION_CODE })
        json.put("collectedAt", System.currentTimeMillis())
        return json
    }

    private fun totalMemMb(): Long? {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / BYTES_PER_MB
    }

    private fun screenJson(): JSONObject {
        val screen = JSONObject()
        val metrics = appContext.resources.displayMetrics
        screen.putOpt("width", safe { metrics.widthPixels })
        screen.putOpt("height", safe { metrics.heightPixels })
        screen.putOpt("density", safe { metrics.density })
        screen.putOpt("refreshRate", safe {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        })
        return screen
    }

    companion object {
        private const val BYTES_PER_MB = 1_048_576L
        private const val DEVICE_ID_FILE = ".device-id"
        private const val INSTALL_ID_FILE = ".install-id"

        @Volatile
        private var instance: DeviceIdentity? = null

        fun get(context: Context): DeviceIdentity {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    DeviceIdentity(
                        appContext = app,
                        deviceId = readOrCreateUuid(File(app.filesDir, DEVICE_ID_FILE)),
                        installId = readOrCreateUuid(File(app.filesDir, INSTALL_ID_FILE)),
                    ).also { instance = it }
                }
            }
        }

        private fun readOrCreateUuid(file: File): String {
            runCatching {
                if (file.exists()) {
                    val existing = file.readText().trim()
                    if (existing.isNotEmpty()) {
                        return UUID.fromString(existing).toString()
                    }
                }
            }
            val fresh = UUID.randomUUID().toString()
            runCatching { file.writeText(fresh) }
            return fresh
        }

        private inline fun <T> safe(block: () -> T): T? = runCatching { block() }.getOrNull()
    }

    private inline fun <T> safe(block: () -> T): T? = runCatching { block() }.getOrNull()
}

package online.automint.app.web

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import online.automint.app.BuildConfig

class WebAppChromeClient(
    private val activity: Activity,
    private val fileChooserLauncher: (Intent, ValueCallback<Array<Uri>>) -> Unit,
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val intent = fileChooserParams.createIntent()
        return try {
            fileChooserLauncher(intent, filePathCallback)
            true
        } catch (_: Exception) {
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (BuildConfig.IS_DEV) {
            android.util.Log.d(
                "WebConsole",
                "${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:" +
                    "${consoleMessage.lineNumber()} ${consoleMessage.message()}"
            )
        }
        return true
    }
}

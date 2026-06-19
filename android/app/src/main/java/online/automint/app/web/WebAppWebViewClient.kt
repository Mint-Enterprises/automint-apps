package online.automint.app.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import online.automint.app.util.UrlAllowlist

class WebAppWebViewClient(
    private val context: Context,
    private val onPageStarted: (String) -> Unit = {},
    private val onPageFinished: (String) -> Unit = {},
    private val onError: (Int, String?) -> Unit = { _, _ -> },
    private val onRenderGone: () -> Unit = {},
    private val onUrlChanged: (String) -> Unit = {},
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false

        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") {
            return handleExternalScheme(view, request.url.toString(), scheme)
        }

        if (UrlAllowlist.isFirstParty(uri)) {
            if (isSignInEntry(uri)) return openInDefaultBrowser(withDesktopCallback(uri))
            return false
        }

        if (UrlAllowlist.isOAuthProvider(uri)) return openInCustomTab(uri)

        return openInCustomTab(uri)
    }

    private fun isSignInEntry(uri: Uri): Boolean {
        if (uri.host?.lowercase() != "automint.online") return false
        val path = uri.path.orEmpty()
        return path.startsWith("/api/auth/signin") ||
            path == "/login" || path.startsWith("/login/")
    }

    private fun withDesktopCallback(uri: Uri): Uri {
        val callback = uri.getQueryParameter("callbackUrl")
        if (callback != null &&
            (callback.contains("/auth/desktop-callback") ||
                callback.contains("/auth/desktop-reauth-callback"))
        ) {
            return uri
        }
        return uri.buildUpon()
            .clearQuery()
            .apply {
                for (k in uri.queryParameterNames) {
                    if (k.equals("callbackUrl", ignoreCase = true)) continue
                    for (v in uri.getQueryParameters(k)) appendQueryParameter(k, v)
                }
                appendQueryParameter("callbackUrl", "/auth/desktop-callback")
            }
            .build()
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageStarted(url)
        onUrlChanged(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        onUrlChanged(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageFinished(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onError(error.errorCode, error.description?.toString())
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRenderGone()
        return true
    }

    private fun handleExternalScheme(view: WebView, url: String, scheme: String): Boolean {
        val intent: Intent = try {
            if (scheme == "intent") {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (_: Exception) {
            return true
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.setPackage(null)
        intent.flags = intent.flags and
            Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrBlank() && isHttpUrl(fallback)) {
                view.loadUrl(fallback)
            }
            return true
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val s = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return s == "http" || s == "https"
    }

    private fun openInDefaultBrowser(uri: Uri): Boolean {
        val browserPackage = resolveDefaultBrowserPackage() ?: return openInCustomTab(uri)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(browserPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            openInCustomTab(uri)
        }
    }

    private fun resolveDefaultBrowserPackage(): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val pkg = context.packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return pkg?.takeIf { it != context.packageName && it != "android" }
    }

    private fun openInCustomTab(uri: Uri): Boolean {
        val tabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        return try {
            tabs.launchUrl(context, uri)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

package online.automint.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.automint.app.bridge.AutomintBridge
import online.automint.app.consent.ConsentManager
import online.automint.app.databinding.ActivityMainBinding
import online.automint.app.identity.DeviceIdentity
import online.automint.app.push.PushTokenRegistrar
import online.automint.app.security.AppLockController
import online.automint.app.security.BiometricGate
import online.automint.app.settings.SettingsActivity
import online.automint.app.settings.SettingsStore
import online.automint.app.splash.NoiseDrawable
import online.automint.app.splash.SplashController
import online.automint.app.telemetry.TelemetryClient
import online.automint.app.identity.AppSignatureVerifier
import online.automint.app.update.AppUpdater
import online.automint.app.update.UpdateUi
import online.automint.app.web.AppUserAgent
import online.automint.app.web.DownloadRouter
import online.automint.app.web.LastLocationStore
import online.automint.app.web.WebAppChromeClient
import online.automint.app.web.WebAppWebViewClient
import org.json.JSONObject

class MainActivity : AppCompatActivity(), UpdateUi {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var appUpdater: AppUpdater
    private lateinit var settings: SettingsStore
    private lateinit var lockController: AppLockController
    private lateinit var splashController: SplashController
    private lateinit var consent: ConsentManager
    private lateinit var lastLocation: LastLocationStore
    private var firstLoadResolved = false

    @Volatile
    private var pageAtTop = true

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (!::webView.isInitialized) return@runOnUiThread
                if (binding.errorView.visibility == View.VISIBLE) {
                    binding.errorView.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    webView.reload()
                }
            }
        }
    }

    private var initialUrl: String = BuildConfig.TARGET_URL

    private var initialLoadStarted = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            else -> null
        }
        pendingFileChooser?.onReceiveValue(uris)
        pendingFileChooser = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Automint)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        lastLocation = LastLocationStore(this)
        lockController = AppLockController(settings)
        consent = ConsentManager(this)
        binding.splashNoise.background = NoiseDrawable()
        splashController = SplashController(
            overlay = binding.splashOverlay,
            aurora = binding.splashAurora,
            logo = binding.splashLogo,
            logoHalo = binding.splashLogoHalo,
            ringFill = binding.splashRingFill,
            wordmarkContainer = binding.splashWordmark,
        )
        splashController.start()
        binding.root.postDelayed({ dismissSplashOnce() }, SPLASH_WATCHDOG_MS)

        webView = binding.webview
        configureWebView()
        setupPullToRefresh()
        registerShortcuts()

        appUpdater = AppUpdater(this, this)
        binding.root.post { AppSignatureVerifier(this).verify() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.lockOverlay.visibility == View.VISIBLE) {
                    moveTaskToBack(true)
                    return
                }
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        }

        binding.unlockButton.setOnClickListener { promptUnlock() }

        val launchIntent = intent
        if (launchIntent != null && isSettingsDeepLink(launchIntent)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (tryHandleAuthCallback(launchIntent)) return

        maybeRequestNotificationPermission()

        maybeShowConsentDisclosure()

        initialUrl = intentUrl(launchIntent)
            ?: lastLocation.restorableUrl()
            ?: BuildConfig.TARGET_URL
        beginIdentityCookieChain()
    }

    private fun startInitialLoad() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        webView.loadUrl(initialUrl)
    }

    private fun beginIdentityCookieChain() {
        val cm = CookieManager.getInstance()
        if (consent.fingerprintAllowed()) {
            val deviceId = DeviceIdentity.get(this).deviceId
            cm.setCookie(
                BuildConfig.TARGET_URL,
                "_amfp_did=$deviceId; Path=/; Secure; SameSite=Lax; Max-Age=$AMFP_MAX_AGE_SECONDS",
            ) {
                cm.flush()
                startInitialLoad()
            }
        } else {
            cm.setCookie(
                BuildConfig.TARGET_URL,
                "_amfp_did=; Path=/; Secure; SameSite=Lax; Max-Age=0",
            ) {
                cm.flush()
                startInitialLoad()
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeShowConsentDisclosure() {
        if (!consent.needsDisclosure()) return
        val privacyUrl = getString(R.string.privacy_policy_url)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.consent_disclosure_title)
            .setMessage(getString(R.string.consent_disclosure_body, privacyUrl))
            .setCancelable(true)
            .setPositiveButton(R.string.consent_continue) { d, _ ->
                consent.markDisclosureShown()
                d.dismiss()
            }
            .setOnDismissListener { consent.markDisclosureShown() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        lockController.onAppForegrounded()
        if (lockController.shouldPrompt()) {
            showLockOverlay()
            promptUnlock()
        } else {
            hideLockOverlay()
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        if (::webView.isInitialized) lastLocation.save(webView.url)
        lockController.onAppBackgrounded()
        if (settings.biometricLockEnabled) {
            showLockOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdater.check(this)
    }

    override fun onDestroy() {
        appUpdater.onDestroy()
        super.onDestroy()
    }

    override fun showUpdateAvailable(versionName: String, onAccept: () -> Unit) {
        binding.updateBannerMessage.text = getString(R.string.update_available_message, versionName)
        binding.updateBannerAction.setOnClickListener { onAccept() }
        binding.updateBanner.visibility = View.VISIBLE
    }

    override fun hideUpdateAvailable() {
        binding.updateBanner.visibility = View.GONE
        binding.updateBannerAction.setOnClickListener(null)
    }

    override fun showUpdateRequired(versionName: String, onAccept: () -> Unit) {
        hideUpdateAvailable()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_required_title)
            .setMessage(R.string.update_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.update_required_action) { _, _ -> onAccept() }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isSettingsDeepLink(intent)) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (tryHandleAuthCallback(intent)) return
        intentUrl(intent)?.let { webView.loadUrl(it) }
    }

    private fun tryHandleAuthCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "automint") return false
        val isCallbackPath = uri.path == "/callback" || uri.path == "/callback/"
        return when {
            uri.host == "auth" && isCallbackPath -> { handleDesktopAuthCallback(uri); true }
            uri.host == "reauth" && isCallbackPath -> { handleDesktopReauthCallback(uri); true }
            else -> false
        }
    }

    private fun handleDesktopAuthCallback(uri: Uri) {
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return
        val exchangeUrl = "${BuildConfig.TARGET_URL.trimEnd('/')}/api/auth/desktop-exchange"
        val payload = JSONObject().put("token", token).toString()

        lifecycleScope.launch {
            val cookies = withContext(Dispatchers.IO) { postDesktopExchange(exchangeUrl, payload) }
            dismissSplashOnce()
            if (cookies != null) {
                val cm = CookieManager.getInstance()
                for (c in cookies) cm.setCookie(BuildConfig.TARGET_URL, c)
                cm.flush()
                webView.loadUrl(BuildConfig.TARGET_URL)
            } else {
                showErrorView()
            }
        }
    }

    private fun postDesktopExchange(url: String, payload: String): List<String>? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (!parsed.protocol.equals("https", ignoreCase = true)) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = EXCHANGE_TIMEOUT_MS
                readTimeout = EXCHANGE_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.headerFields
                    .filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }
                    .values.flatten()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun handleDesktopReauthCallback(uri: Uri) {
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return
        dismissSplashOnce()
        val exchangeUrl = "${BuildConfig.TARGET_URL.trimEnd('/')}/api/reauth/desktop-exchange"
        val js = """
            (function () {
              fetch(${JSONObject.quote(exchangeUrl)}, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ token: ${JSONObject.quote(token)} })
              }).then(function (r) {
                if (!r.ok) return;
                var rt = new URLSearchParams(location.search).get('returnTo');
                if (rt) {
                  try {
                    var u = new URL(rt, location.origin);
                    if (u.origin === location.origin) {
                      location.href = u.pathname + u.search + u.hash;
                      return;
                    }
                  } catch (e) {}
                }
                location.reload();
              }).catch(function () {});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.userAgentString = "${s.userAgentString} ${AppUserAgent.suffix}"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.IS_DEV)

        webView.webViewClient = WebAppWebViewClient(
            context = this,
            onPageFinished = { url ->
                dismissSplashOnce()
                binding.swipeRefresh.isRefreshing = false
                webView.evaluateJavascript(SCROLL_PROBE_JS, null)
                if (isFirstPartyOrigin(url)) PushTokenRegistrar.register(this)
                lastLocation.save(url)
            },
            onError = { code, _ ->
                TelemetryClient.getInstance(this)
                    .track(TelemetryClient.EVENT_CONTENT_LOAD_FAILED, JSONObject().put("code", code))
                binding.swipeRefresh.isRefreshing = false
                showErrorView()
                dismissSplashOnce()
            },
            onRenderGone = { recreateWebView() },
            onUrlChanged = { pageAtTop = true },
        )
        webView.webChromeClient = WebAppChromeClient(
            activity = this,
            fileChooserLauncher = { intent, callback ->
                pendingFileChooser = callback
                fileChooser.launch(intent)
            },
        )
        webView.addJavascriptInterface(
            AutomintBridge(
                context = this,
                settings = settings,
                onReload = { webView.reload() },
                onClearSession = {
                    webView.clearCache(true)
                    webView.clearHistory()
                    PushTokenRegistrar.forget(this)
                },
                originAllowed = { isFirstPartyOrigin(webView.url) },
                fingerprintAllowed = { consent.fingerprintAllowed() },
                onScrollAtTopChanged = { atTop -> pageAtTop = atTop },
            ),
            AutomintBridge.NAME,
        )

        maybeAddDocumentStartGlobal()

        webView.setDownloadListener(DownloadRouter(this))
    }

    private fun isFirstPartyOrigin(url: String?): Boolean {
        val host = runCatching { Uri.parse(url ?: return false).host }.getOrNull() ?: return false
        val targetHost = runCatching { Uri.parse(BuildConfig.TARGET_URL).host }.getOrNull() ?: return false
        return host.equals(targetHost, ignoreCase = true)
    }

    private fun maybeAddDocumentStartGlobal() {
        if (!consent.fingerprintAllowed()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origin = runCatching {
            val u = Uri.parse(BuildConfig.TARGET_URL)
            "${u.scheme}://${u.host}"
        }.getOrNull() ?: return
        val fingerprintJson = DeviceIdentity.get(this).fingerprintJson()
        val safeJson = fingerprintJson.replace("<", "\\u003c")
        val script =
            "window.automintAndroid = Object.freeze({ isAndroid: true, fingerprint: $safeJson });"
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        }
    }

    private fun showErrorView() {
        binding.errorView.visibility = View.VISIBLE
        binding.webview.visibility = View.GONE
    }

    private fun dismissSplashOnce() {
        if (firstLoadResolved) return
        firstLoadResolved = true
        splashController.dismiss()
    }

    private fun showLockOverlay() {
        dismissSplashOnce()
        binding.lockOverlay.visibility = View.VISIBLE
    }

    private fun hideLockOverlay() {
        binding.lockOverlay.visibility = View.GONE
    }

    private fun promptUnlock() {
        if (!BiometricGate.canAuthenticate(this)) {
            lockController.markAuthenticated()
            hideLockOverlay()
            return
        }
        BiometricGate.prompt(
            activity = this,
            title = getString(R.string.biometric_unlock_title),
            subtitle = getString(R.string.biometric_unlock_subtitle),
            onSuccess = {
                lockController.markAuthenticated()
                hideLockOverlay()
            },
            onFailure = {},
            onCancel = {
                moveTaskToBack(true)
            },
        )
    }

    private fun recreateWebView() {
        val last = webView.url ?: BuildConfig.TARGET_URL
        val parent = binding.swipeRefresh
        parent.removeView(webView)
        webView.destroy()

        val fresh = WebView(this).apply { id = binding.webview.id }
        fresh.layoutParams = binding.webview.layoutParams
        parent.addView(fresh, 0)
        webView = fresh
        configureWebView()
        webView.loadUrl(last)
    }

    private fun setupPullToRefresh() {
        binding.swipeRefresh.setOnRefreshListener { webView.reload() }
        binding.swipeRefresh.setColorSchemeColors(Color.parseColor("#65D639"))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1A1B20"))
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.canScrollVertically(-1) || !pageAtTop
        }
    }

    private fun registerShortcuts() {
        val shortcuts = listOf(
            shortcut("new_deal", R.string.shortcut_new_deal, R.drawable.ic_shortcut_new_deal, "/escrow"),
            shortcut("my_deals", R.string.shortcut_my_deals, R.drawable.ic_shortcut_deals, "/dashboard/deals"),
            shortcut("chat", R.string.shortcut_chat, R.drawable.ic_shortcut_chat, "/server"),
            shortcut("mintmp", R.string.shortcut_mintmp, R.drawable.ic_shortcut_mintmp, "/mintmp"),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts) }
    }

    private fun shortcut(id: String, labelRes: Int, iconRes: Int, path: String): ShortcutInfoCompat {
        val label = getString(labelRes)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("${BuildConfig.TARGET_URL.trimEnd('/')}$path")
        }
        return ShortcutInfoCompat.Builder(this, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(this, iconRes))
            .setIntent(intent)
            .build()
    }

    private fun isSettingsDeepLink(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        return uri.scheme == "automint" && uri.host == "settings"
    }

    companion object {
        private const val SPLASH_WATCHDOG_MS = 8_000L

        private const val EXCHANGE_TIMEOUT_MS = 15_000

        private const val AMFP_MAX_AGE_SECONDS = 15_552_000L

        private val SCROLL_PROBE_JS = """
            (function () {
              var n = window.AutomintNative;
              if (!n || typeof n.setPageAtTop !== 'function') return;
              if (window.__amScrollProbe) return;
              window.__amScrollProbe = true;

              var last = null;
              function report(atTop) {
                atTop = !!atTop;
                if (atTop === last) return;
                last = atTop;
                try { n.setPageAtTop(atTop); } catch (e) {}
              }
              function rootTop() {
                var r = document.scrollingElement || document.documentElement;
                return (r && r.scrollTop) || window.scrollY || 0;
              }
              document.addEventListener('scroll', function (e) {
                var t = e.target;
                if (t === document || t === window || t === document.documentElement) {
                  report(rootTop() <= 0);
                } else {
                  report(((t && t.scrollTop) || 0) <= 0 && rootTop() <= 0);
                }
              }, { capture: true, passive: true });
              document.addEventListener('touchstart', function (e) {
                var t = e.touches && e.touches[0];
                if (!t) return;
                var el = document.elementFromPoint(t.clientX, t.clientY);
                while (el && el.nodeType === 1) {
                  if (el.scrollTop > 0) {
                    var oy = '';
                    try { oy = getComputedStyle(el).overflowY; } catch (_) {}
                    if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') { report(false); return; }
                  }
                  el = el.parentElement;
                }
                report(rootTop() <= 0);
              }, { capture: true, passive: true });

              report(rootTop() <= 0);
            })();
        """.trimIndent()
    }

    private fun intentUrl(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme == "automint" && uri.host == "settings") return null
        return when (uri.scheme) {
            "https" -> if (uri.host == "automint.online") uri.toString() else null
            "automint" -> {
                val rest = (uri.authority.orEmpty() + uri.path.orEmpty()).trimStart('/')
                "https://automint.online/$rest" +
                    (uri.query?.let { "?$it" } ?: "") +
                    (uri.fragment?.let { "#$it" } ?: "")
            }
            else -> null
        }
    }
}

package com.example.demopartnerapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.demopartnerapp.bridge.BridgeLocalStorage
import com.example.demopartnerapp.bridge.JavaScriptInterfaceee
import com.example.demopartnerapp.bridge.PwaKeys
import com.example.demopartnerapp.bridge.WebViewMethodHandler
import com.example.demopartnerapp.databinding.ActivityPwaSsoBinding
import org.json.JSONObject
import java.io.File

/**
 * Model B — PWA SSO, with the full ekincare JS bridge wired in.
 *
 * Flow: build the partner payload -> AES-256-GCM encrypt -> load the PWA's
 * /pwa-login with slug/message/auth_tag. The PWA posts to
 * /v1/customers/pwa-sso/:slug itself, persists the session, and redirects to the
 * deeplink — ekincare's login screen is skipped.
 *
 * The WebView injects `ekincareAndroidInterface` (mirroring EkincarePwa's
 * PwaWebViewActivity). Confirmed against the PWA source: SSO session persistence
 * has NO isEkincareApp() branch (PwaSSOLoginPage.js) — in fact the SSO page
 * CALLS the bridge (saveHeaders/saveCustomer) to export its web session to
 * native. So the bridge is expected and does NOT bounce SSO to login. The one
 * load-bearing seed is APP-VERSION (see BridgeLocalStorage) — the 401
 * "Please update your app" gate. All bridge calls the PWA makes (permissions,
 * location, share, etc.) are dispatched by WebViewMethodHandler.
 */
class PwaSsoActivity :
    AppCompatActivity(),
    JavaScriptInterfaceee.NativeBridgeListener,
    WebViewMethodHandler.BridgeHost {

    private lateinit var b: ActivityPwaSsoBinding
    private lateinit var methodHandler: WebViewMethodHandler

    // WebViewMethodHandler.BridgeHost
    override val hostActivity: AppCompatActivity get() = this
    override val hostWebView: WebView get() = b.webView

    // Pending navigator.geolocation grant (HTML5 geolocation prompt).
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // Pending bridge permission request — JS names to report back after the prompt resolves.
    private var pendingBridgePermNames: List<String> = emptyList()

    private val geoPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
            if (!granted) toast("Location permission denied")
        }

    private val bridgePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Recompute + report whatever the PWA asked about, granted or not.
            methodHandler.reportPermissionStatuses(pendingBridgePermNames)
            pendingBridgePermNames = emptyList()
        }

    private val bridgeLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            methodHandler.reportLocation()
        }

    // <input type="file"> support — the WebChromeClient hands us the callback,
    // we open the system picker and return the chosen Uri(s) to the page.
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            // parseResult handles both a single Uri and multi-select (clipData); null on cancel.
            val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            cb.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityPwaSsoBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyEdgeToEdgeInsets(b.root)
        title = "PWA SSO"
        methodHandler = WebViewMethodHandler(this)

        prefill()
        b.btnLaunch.setOnClickListener { launch() }
    }

    private fun prefill() {
        b.etPwaHost.setText(BuildConfig.EKIN_PWA_HOST)
        b.etSlug.setText(BuildConfig.EKIN_PARTNER_SLUG)
        b.etKey.setText(BuildConfig.EKIN_ENCODED_KEY)
        b.etIv.setText(BuildConfig.EKIN_ENCODED_IV)
        b.etEntityId.setText(BuildConfig.EKIN_ENTITY_ID)
        // Sample customer — SSO creates/updates this user under the entity on login. Edit at runtime.
        b.etEmail.setText("demo.user@ekincare.com")
        b.etFirstName.setText("Demo")
        b.etLastName.setText("User")
        b.etGender.setText("Male")
        b.etMobile.setText("9999900001")
        b.etMemberId.setText("DEMO-001")
        b.etDob.setText("1990-01-15")
        b.etDeeplink.setText("benefits")
    }

    private fun launch() {
        val host = b.etPwaHost.text.toString().trim().trimEnd('/')
        val slug = b.etSlug.text.toString().trim()
        val key = b.etKey.text.toString().trim()
        val iv = b.etIv.text.toString().trim()

        if (host.isEmpty() || slug.isEmpty() || key.isEmpty() || iv.isEmpty()) {
            toast("host, slug, key and iv are required")
            return
        }

        // requested_at must be within 30s of the server clock (replay protection).
        val payload = JSONObject().apply {
            put("entity_id", b.etEntityId.text.toString().trim())
            put("email", b.etEmail.text.toString().trim())
            put("first_name", b.etFirstName.text.toString().trim())
            put("last_name", b.etLastName.text.toString().trim())
            put("gender", b.etGender.text.toString().trim())
            put("mobile", b.etMobile.text.toString().trim())
            put("member_id", b.etMemberId.text.toString().trim())
            put("dob", b.etDob.text.toString().trim())
            put("deeplink", b.etDeeplink.text.toString().trim())
            put("requested_at", System.currentTimeMillis() / 1000)
        }

        val (message, authTag) = try {
            AesGcm.encrypt(payload.toString(), key, iv)
        } catch (e: Exception) {
            toast("Encrypt failed: ${e.message}")
            return
        }

        // Build the query manually. message/authTag are URL-safe base64 (chars: A-Za-z0-9-_ and '=' padding).
        // appendQueryParameter() would percent-encode the '=' padding to %3D, and the PWA's queryParams()
        // util forwards the raw value WITHOUT decoding -> backend urlsafe_decode64 fails ("Unable to
        // process the login request"). A literal '=' is safe in a query value and round-trips intact.
        val url = "$host/pwa-login?slug=$slug&message=$message&auth_tag=$authTag"

        b.tvDebug.text = "payload=${payload}\n\nURL=$url"
        openWebView(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebView(url: String) {
        b.formScroll.visibility = android.view.View.GONE
        b.btnLaunch.visibility = android.view.View.GONE
        b.webView.visibility = android.view.View.VISIBLE
        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // PWA needs localStorage/sessionStorage
            databaseEnabled = true
            setGeolocationEnabled(true)       // allow navigator.geolocation
            mediaPlaybackRequiresUserGesture = false
            // Honor the PWA's <meta viewport width=device-width>. Without these the
            // WebView lays out at a default (wider) width, so responsive full-width
            // elements (e.g. the fixed bottom nav: left-1/2 + w-100%) overflow and
            // get clipped on the right. Mirrors the ekincare app's PwaWebViewActivity.
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Inject the bridge exactly like EkincarePwa's PwaWebViewActivity.
        b.webView.addJavascriptInterface(JavaScriptInterfaceee(this), PwaKeys.JS_INTERFACE)

        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                b.webView.clearFocus()
                hideKeyboard()
                // Seed device identity + APP-VERSION before the PWA runs (getLocalStorageDeviceId parity).
                view?.evaluateJavascript(BridgeLocalStorage.getLocalStorageDeviceId(this@PwaSsoActivity), null)
            }
            override fun onPageFinished(view: WebView, u: String?) {
                super.onPageFinished(view, u)
                view.evaluateJavascript(BridgeLocalStorage.getLocalStorageDeviceId(this@PwaSsoActivity), null)
            }
        }

        // Grant HTML5 geolocation. Plain WebView denies it by default -> "location not detected".
        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    this@PwaSsoActivity, Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    geoPermLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            }

            // <input type="file"> — open the system picker (honors accept types +
            // multiple from the page) and return the result to the WebView.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)   // cancel any pending request
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params?.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    toast("No app available to pick a file")
                    false
                }
            }
        }

        // Blob/data downloads (PWA hands blobs to native via getBase64FromBlobData).
        b.webView.setDownloadListener { downloadUrl, _, _, mimeType, _ ->
            if (downloadUrl.startsWith("blob")) {
                b.webView.evaluateJavascript(
                    JavaScriptInterfaceee.getBase64StringFromBlobUrl(downloadUrl, mimeType), null,
                )
            } else {
                toast("Download: $downloadUrl")
            }
        }

        b.webView.loadUrl(url)
    }

    // ---- JavaScriptInterfaceee.NativeBridgeListener ----

    override fun onNativeMethod(data: String?) {
        // Called off the main thread by the WebView JS bridge; handler hops to main where needed.
        runOnUiThread { methodHandler.handleOnNativeMethod(data) }
    }

    override fun onBlobBase64(base64Data: String?) {
        if (base64Data.isNullOrBlank()) return
        try {
            // Format: data:<mime>;base64,<payload>
            val comma = base64Data.indexOf(',')
            val raw = if (comma >= 0) base64Data.substring(comma + 1) else base64Data
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, "ekincare_${System.currentTimeMillis()}")
            file.writeBytes(bytes)
            runOnUiThread { toast("Saved: ${file.absolutePath}") }
        } catch (e: Exception) {
            Log.e("PwaBridge", "blob save failed: ${e.message}")
        }
    }

    // ---- WebViewMethodHandler.BridgeHost async ops ----

    override fun promptPermissions(androidPerms: Array<String>, jsNames: List<String>) {
        pendingBridgePermNames = jsNames
        bridgePermLauncher.launch(androidPerms)
    }

    override fun promptLocationThenReport() {
        bridgeLocationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    // ---- lifecycle / input ----

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (b.webView.visibility == android.view.View.VISIBLE && b.webView.canGoBack()) {
            b.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // Tap outside the focused field -> drop focus + close keyboard.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val r = Rect()
                focused.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = (currentFocus ?: b.root).windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

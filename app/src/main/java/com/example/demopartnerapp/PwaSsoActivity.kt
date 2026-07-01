package com.example.demopartnerapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.demopartnerapp.databinding.ActivityPwaSsoBinding
import org.json.JSONObject

/**
 * Model B — PWA SSO.
 * Build the partner payload -> AES-256-GCM encrypt -> load the PWA's /pwa-login
 * page with slug/message/auth_tag. The PWA frontend (PwaSSOLoginPage) posts to
 * /v1/customers/pwa-sso/:slug itself, stores the session, and redirects to the
 * deeplink — so ekincare's own login screen is skipped. All fields prefilled
 * with a working staging config; edit any of them before launch.
 *
 * NOTE: the SSO page is loaded in a PLAIN WebView (no `ekincareAndroidInterface`
 * JS bridge). This is exactly what a partner does — open a URL in a webview. If
 * we inject that bridge (as the full-app PwaWebHostActivity does), the PWA
 * detects itself as the native ekincare app (isAndroidWebView/isEkincareApp) and
 * expects NATIVE to own the session, so the web SSO session is ignored and the
 * PWA falls back to its login page. Keep this plain.
 */
class PwaSsoActivity : AppCompatActivity() {

    private lateinit var b: ActivityPwaSsoBinding

    // Pending navigator.geolocation grant — resolved after the runtime permission prompt.
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
            if (!granted) toast("Location permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPwaSsoBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "PWA SSO"

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
        // Plain WebView — NO ekincareAndroidInterface. The PWA stays in "web" mode and
        // persists the SSO session itself (localStorage) -> ekincare login skipped.
        b.formScroll.visibility = android.view.View.GONE
        b.btnLaunch.visibility = android.view.View.GONE
        b.webView.visibility = android.view.View.VISIBLE
        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // PWA needs localStorage/sessionStorage
            databaseEnabled = true
            setGeolocationEnabled(true)       // allow navigator.geolocation
        }
        b.webView.webViewClient = object : WebViewClient() {
            // Close the soft keyboard on every navigation / refresh.
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                b.webView.clearFocus()
                hideKeyboard()
                // Seed APP-VERSION before the PWA runs (see APP_VERSION_SHIM).
                view?.evaluateJavascript(APP_VERSION_SHIM, null)
            }
            override fun onPageFinished(view: WebView, u: String?) {
                super.onPageFinished(view, u)
                view.evaluateJavascript(APP_VERSION_SHIM, null)
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
                    locationPermLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            }
        }
        b.webView.loadUrl(url)
    }

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

    private companion object {
        // Minimum app version the backend accepts (base_mobile_api_controller: UNSUPPORTED_APP_VERSION).
        // Must be >= that. The app-test PWA build sends an `app-version` header from
        // localStorage['APP-VERSION']; a plain WebView never seeds it, so the backend's
        // check_old_app_version gate returns 401 "Please update your app" -> the PWA treats the
        // 401 as session-expiry, wipes the session, and redirects to /login. Seed a valid version
        // (as the native app does via getLocalStorageDeviceId) so data calls pass and the SSO
        // session survives -> lands on the deeplink.
        const val APP_VERSION_SHIM = """
            (function(){ try { window.localStorage.setItem('APP-VERSION', '99.9.9'); } catch(e){} })();
        """
    }
}

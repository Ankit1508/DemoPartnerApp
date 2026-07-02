package com.example.demopartnerapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.demopartnerapp.bridge.BridgeLocalStorage
import com.example.demopartnerapp.bridge.JavaScriptInterfaceee
import com.example.demopartnerapp.bridge.PwaKeys
import com.example.demopartnerapp.bridge.WebViewMethodHandler
import com.example.demopartnerapp.databinding.ActivityPwaSsoBinding
import kotlinx.coroutines.launch
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
    // we open the system picker (with a camera capture option merged in) and
    // return the chosen Uri(s) to the page.
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingChooserParams: WebChromeClient.FileChooserParams? = null
    private var cameraOutputUri: Uri? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
                // A picked file comes back in the Intent; a camera capture returns no
                // data (the image was written to cameraOutputUri we handed the camera).
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    ?: cameraOutputUri?.let { arrayOf(it) }
            } else {
                null
            }
            cb.onReceiveValue(uris)
            cameraOutputUri = null
        }

    // ACTION_IMAGE_CAPTURE needs CAMERA granted because we declare it in the manifest.
    private val cameraPermForChooserLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            launchFileChooser(pendingChooserParams, withCamera = granted)
        }

    // Connect wearable via Health Connect (steps + calories).
    private val healthConnect by lazy { HealthConnectHelper(this, b.webView) }

    private val healthPermLauncher =
        registerForActivityResult(
            androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.containsAll(healthConnect.permissions)) {
                healthConnect.notifyConnected()
                lifecycleScope.launch { healthConnect.syncSteps() }
                toast("Wearable connected")
            } else {
                toast("Health permissions denied")
            }
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

    private val formPrefs by lazy { getSharedPreferences("pwa_sso_form", MODE_PRIVATE) }

    // Persisted key -> field. Same order used for prefill + save.
    private fun formFields(): List<Pair<String, EditText>> = listOf(
        "host" to b.etPwaHost,
        "slug" to b.etSlug,
        "key" to b.etKey,
        "iv" to b.etIv,
        "entity_id" to b.etEntityId,
        "email" to b.etEmail,
        "first_name" to b.etFirstName,
        "last_name" to b.etLastName,
        "gender" to b.etGender,
        "mobile" to b.etMobile,
        "member_id" to b.etMemberId,
        "dob" to b.etDob,
        "deeplink" to b.etDeeplink,
    )

    // First-run defaults (BuildConfig config + a sample customer). Used until the
    // user fills the form once; after that the last-entered values take over.
    private fun formDefaults(): Map<String, String> = mapOf(
        "host" to BuildConfig.EKIN_PWA_HOST,
        "slug" to BuildConfig.EKIN_PARTNER_SLUG,
        "key" to BuildConfig.EKIN_ENCODED_KEY,
        "iv" to BuildConfig.EKIN_ENCODED_IV,
        "entity_id" to BuildConfig.EKIN_ENTITY_ID,
        "email" to "demo.user@ekincare.com",
        "first_name" to "Demo",
        "last_name" to "User",
        "gender" to "Male",
        "mobile" to "9999900001",
        "member_id" to "DEMO-001",
        "dob" to "1990-01-15",
        "deeplink" to "benefits",
    )

    /** Prefill from the last-saved values, falling back to first-run defaults. */
    private fun prefill() {
        val defaults = formDefaults()
        formFields().forEach { (key, field) ->
            field.setText(formPrefs.getString(key, defaults[key]))
        }
    }

    /** Persist the current field values so they're prefilled next time. */
    private fun saveDetails() {
        formPrefs.edit().apply {
            formFields().forEach { (key, field) -> putString(key, field.text.toString()) }
        }.apply()
    }

    private fun launch() {
        saveDetails()   // remember what the user entered for next time
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
            // multiple from the page), with a camera capture option merged in for
            // image inputs. Returns the result to the WebView.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)   // cancel any pending request
                filePathCallback = callback
                pendingChooserParams = params
                if (acceptsImage(params)) {
                    // Offer camera only if a camera exists; gate on CAMERA permission first.
                    if (hasCamera()) {
                        if (isGranted(Manifest.permission.CAMERA)) {
                            launchFileChooser(params, withCamera = true)
                        } else {
                            cameraPermForChooserLauncher.launch(Manifest.permission.CAMERA)
                        }
                        return true
                    }
                }
                return launchFileChooser(params, withCamera = false)
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

    override fun connectHealthConnect() {
        if (!healthConnect.isAvailable()) {
            if (healthConnect.providerUpdateRequired()) openHealthConnectInStore()
            else toast("Health Connect not available on this device")
            return
        }
        lifecycleScope.launch {
            if (healthConnect.hasAllPermissions()) {
                healthConnect.notifyConnected()
                healthConnect.syncSteps()
                toast("Wearable connected")
            } else {
                healthPermLauncher.launch(healthConnect.permissions)
            }
        }
    }

    override fun syncHealthSteps() {
        lifecycleScope.launch { healthConnect.syncSteps() }
    }

    override fun disconnectHealthConnect() {
        healthConnect.notifyDisconnected()
        lifecycleScope.launch { healthConnect.revoke() }
        toast("Wearable disconnected")
    }

    private fun openHealthConnectInStore() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { toast("Install Health Connect to connect a wearable") }
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

    // ---- file chooser (with camera capture merged in) ----

    private fun launchFileChooser(
        params: WebChromeClient.FileChooserParams?,
        withCamera: Boolean,
    ): Boolean {
        val content = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        val chooser = Intent.createChooser(content, "Select or capture")
        if (withCamera) {
            val cam = createCameraIntent()
            if (cam != null && cam.resolveActivity(packageManager) != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
            } else {
                cameraOutputUri = null
            }
        }
        return try {
            fileChooserLauncher.launch(chooser)
            true
        } catch (e: Exception) {
            filePathCallback?.onReceiveValue(null)   // don't leave the page input hanging
            filePathCallback = null
            cameraOutputUri = null
            toast("No app available to pick a file")
            false
        }
    }

    /** ACTION_IMAGE_CAPTURE writing to a FileProvider Uri we then hand back to the WebView. */
    private fun createCameraIntent(): Intent? = try {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraOutputUri = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    } catch (e: Exception) {
        cameraOutputUri = null
        null
    }

    private fun acceptsImage(params: WebChromeClient.FileChooserParams?): Boolean {
        val types = params?.acceptTypes
        if (types.isNullOrEmpty() || types.all { it.isBlank() }) return true  // unrestricted
        return types.any { it.startsWith("image/") || it == "*/*" || it.contains("image") }
    }

    private fun hasCamera() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun isGranted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        super.onPause()
        // Persist edits even if the user leaves without tapping Launch.
        saveDetails()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = (currentFocus ?: b.root).windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

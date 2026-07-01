package com.example.demopartnerapp.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatches every {action, payload} the PWA posts over the bridge.
 * Mirrors EkincarePwa's WebViewMethodHandler (the giant `when` switch keyed on
 * `action`). Actions a plain partner demo can service (permissions, location,
 * share, external url, status bar, close, analytics, saveHeaders, blob download)
 * are handled for real. SDK-bound actions (payment / video / health-connect /
 * biometric) are acknowledged with a Toast + the JS callback the PWA expects,
 * so the PWA flow never hangs waiting on native.
 */
class WebViewMethodHandler(private val host: BridgeHost) {

    /** Implemented by the WebView activity — owns the runtime-permission launchers. */
    interface BridgeHost {
        val hostActivity: AppCompatActivity
        val hostWebView: WebView

        /** Fire the runtime prompt for [androidPerms]; report [jsNames] back when it resolves. */
        fun promptPermissions(androidPerms: Array<String>, jsNames: List<String>)

        /** Prompt for location if needed, then have the handler report coords. */
        fun promptLocationThenReport()
    }

    private val ctx: Context get() = host.hostActivity
    private val web: WebView get() = host.hostWebView

    fun handleOnNativeMethod(data: String?) {
        if (data.isNullOrBlank()) return
        val (action, payload) = HandleHeaderFromScript.handleScriptData(data)
        Log.d(TAG, "dispatch action=$action payload=$payload")

        when (action.lowercase()) {
            "close" -> host.hostActivity.finish()

            "fetchpermissionsdata" -> reportPermissionStatuses(parseJsPermNames(payload))

            "requestpermissions", "requestapppermissions" -> {
                val jsNames = parseJsPermNames(payload)
                val android = jsNames.flatMap { androidPermsFor(it) }
                    .filter { needsRequest(it) }
                    .distinct()
                if (android.isEmpty()) reportPermissionStatuses(jsNames)
                else host.promptPermissions(android.toTypedArray(), jsNames)
            }

            "fetchlocation" -> {
                if (hasLocationPermission()) reportLocation()
                else host.promptLocationThenReport()
            }

            "trackevent" -> Log.d(TAG, "trackEvent (demo no-op) payload=$payload")

            "dashboardpageload" -> Log.d(TAG, "dashboardPageLoad — PWA dashboard ready")

            "saveheaders" -> saveHeaders(payload)
            "savecustomer" -> Log.d(TAG, "saveCustomer (demo — session already web-persisted)")

            "externalurl" -> openExternalUrl(payload)
            "sharelink", "nativeshare" -> shareLink(payload)
            "setstatusbarcolor" -> setStatusBarColor(payload)

            "enablemfa", "defaultenablemfa" -> {
                toast("MFA enable requested (demo)")
                JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.mfaEnabled())
            }
            "disablemfa" -> {
                toast("MFA disable requested (demo)")
                JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.mfaDisabled())
            }

            "collectpayment" -> {
                toast("Payment not supported in demo")
                JavaScriptEvaluator.sendToPwa(
                    web, JavaScriptCodeBuilder.paymentFailure(0, "Not supported in demo app"),
                )
            }

            "disconnecthealthconnect" ->
                JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.disconnectHealthConnect())
            "disconnectnativewearable" ->
                JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.disconnectNativeWearable())

            "connecthealthconnectwearable", "connectnativewearable", "syncsteps" ->
                toast("Wearable/steps not supported in demo")

            "joinstreamcall", "endstreamcall" -> toast("Video calls not supported in demo")
            "open_qr_scanner" -> toast("QR scanner not supported in demo")
            "playstorerating" -> toast("Play Store rating (demo)")
            "open-support" -> toast("Support chat not supported in demo")
            "showpermissioninfo" -> toast("Permission info (demo)")
            "backtohealthcoach", "ensuremiuicallpermissions" ->
                Log.d(TAG, "no-op in demo: $action")

            else -> Log.w(TAG, "Unhandled bridge action: $action")
        }
    }

    // ---- native -> JS reporters (also called by the activity after a prompt resolves) ----

    fun reportPermissionStatuses(jsNames: List<String>) {
        if (jsNames.isEmpty()) return
        val statuses = jsNames.associateWith { name ->
            val perms = androidPermsFor(name)
            when {
                perms.isEmpty() -> "prompt"                 // e.g. "call" — not modelled in demo
                perms.all { isGranted(it) } -> "granted"
                else -> "denied"
            }
        }
        JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.permissionsData(statuses))
    }

    fun reportLocation() {
        if (!hasLocationPermission()) {
            JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.locationData("denied", null, null))
            return
        }
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getProviders(true).asSequence()
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (loc != null) {
                JavaScriptEvaluator.sendToPwa(
                    web, JavaScriptCodeBuilder.locationData("granted", loc.latitude, loc.longitude),
                )
            } else {
                JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.locationData("failed", null, null))
            }
        } catch (e: SecurityException) {
            JavaScriptEvaluator.sendToPwa(web, JavaScriptCodeBuilder.locationData("denied", null, null))
        }
    }

    // ---- action helpers ----

    private fun saveHeaders(payload: String) {
        // The PWA exports its (already-established) web session here. Demo just logs it.
        val ek = runCatching { JSONObject(payload).optString("x-ekincare-key") }.getOrDefault("")
        Log.d(TAG, "saveHeaders received (ekincare-key present=${ek.isNotEmpty()})")
    }

    private fun openExternalUrl(payload: String) {
        val url = extractString(payload, "url") ?: payload.trim().ifEmpty { return }
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { toast("Cannot open: $url") }
    }

    private fun shareLink(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        val link = json?.optString("link").orEmpty()
        val msg = json?.optString("shareMessage").orEmpty()
        val text = listOf(msg, link).filter { it.isNotBlank() }.joinToString("\n")
            .ifBlank { payload }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(share, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun setStatusBarColor(payload: String) {
        val color = extractString(payload, "color") ?: return
        host.hostActivity.runOnUiThread {
            runCatching { host.hostActivity.window.statusBarColor = Color.parseColor(color) }
        }
    }

    // ---- permission plumbing ----

    private fun parseJsPermNames(payload: String): List<String> {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = json.optJSONArray("permissions") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it).lowercase() }
    }

    /** Map a PWA permission name to the concrete Android permission(s). Empty = not modelled. */
    private fun androidPermsFor(name: String): List<String> = when (name) {
        "camera" -> listOf(Manifest.permission.CAMERA)
        "audio", "microphone", "record_audio" -> listOf(Manifest.permission.RECORD_AUDIO)
        "geolocation", "location" -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        "notification" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "storage" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.READ_MEDIA_IMAGES) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyList()   // "call" etc. — not modelled in the demo
    }

    private fun isGranted(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun needsRequest(perm: String) = !isGranted(perm)

    private fun hasLocationPermission() =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    // ---- misc ----

    private fun extractString(payload: String, key: String): String? =
        runCatching { JSONObject(payload).optString(key).ifBlank { null } }.getOrNull()

    private fun toast(m: String) =
        host.hostActivity.runOnUiThread { Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val TAG = "PwaBridge"
    }
}

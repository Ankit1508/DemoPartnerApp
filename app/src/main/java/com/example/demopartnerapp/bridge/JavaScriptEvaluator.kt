package com.example.demopartnerapp.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * Native -> JS. Always evaluates on the main thread (WebView requirement).
 * Mirrors EkincarePwa's JavaScriptEvaluator.
 */
object JavaScriptEvaluator {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun evaluate(webView: WebView, js: String, callback: ((String?) -> Unit)? = null) {
        mainHandler.post {
            try {
                webView.evaluateJavascript(js) { result -> callback?.invoke(result) }
            } catch (e: Exception) {
                Log.e("PwaBridge", "evaluateJavascript failed: ${e.message}")
            }
        }
    }

    /** Deliver a {action,payload} envelope to the PWA via window.handleNativeMessage(...). */
    fun sendToPwa(webView: WebView, envelopeJson: String) {
        // JSONObject.quote-style escaping so the payload survives as a JS string literal.
        val escaped = org.json.JSONObject.quote(envelopeJson)
        evaluate(webView, "if (window.${PwaKeys.JS_RECEIVER}) { window.${PwaKeys.JS_RECEIVER}($escaped); }")
        Log.d("PwaBridge", "handleNativeMessage -> $envelopeJson")
    }
}

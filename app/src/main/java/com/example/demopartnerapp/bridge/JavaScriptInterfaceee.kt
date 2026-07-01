package com.example.demopartnerapp.bridge

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The object injected into the PWA as `window.ekincareAndroidInterface`.
 * Single entry point `postMessage(String)` — the PWA stringifies
 * {action, payload} and calls it. We forward the raw string to the listener
 * (PwaSsoActivity), which dispatches via WebViewMethodHandler.
 *
 * Named to mirror EkincarePwa's JavaScriptInterfaceee for easy cross-reference.
 * `@JavascriptInterface` is mandatory on every method the PWA can reach.
 */
class JavaScriptInterfaceee(
    private val listener: NativeBridgeListener,
) {

    interface NativeBridgeListener {
        /** Raw {action,payload} JSON from window.ekincareAndroidInterface.postMessage(...). */
        fun onNativeMethod(data: String?)

        /** Base64 blob handed back from getBase64StringFromBlobUrl (file download). */
        fun onBlobBase64(base64Data: String?)
    }

    @JavascriptInterface
    fun postMessage(data: String?) {
        Log.d("PwaBridge", "postMessage <- $data")
        listener.onNativeMethod(data)
    }

    /** PWA hands a blob's base64 back here so native can save it (see ReusableDownloadListener flow). */
    @JavascriptInterface
    fun getBase64FromBlobData(base64Data: String?) {
        listener.onBlobBase64(base64Data)
    }

    companion object {
        /**
         * JS that fetches a blob URL, base64-encodes it and returns it via
         * ekincareAndroidInterface.getBase64FromBlobData(...). Mirrors EkincarePwa.
         */
        fun getBase64StringFromBlobUrl(blobUrl: String, mimeType: String): String {
            if (!blobUrl.startsWith("blob")) return ""
            return """
                (function() {
                  var xhr = new XMLHttpRequest();
                  xhr.open('GET', '$blobUrl', true);
                  xhr.setRequestHeader('Content-type', '$mimeType');
                  xhr.responseType = 'blob';
                  xhr.onload = function() {
                    if (this.status == 200) {
                      var blob = this.response;
                      var reader = new FileReader();
                      reader.readAsDataURL(blob);
                      reader.onloadend = function() {
                        ${PwaKeys.JS_INTERFACE}.getBase64FromBlobData(reader.result);
                      };
                    }
                  };
                  xhr.send();
                })();
            """.trimIndent()
        }
    }
}

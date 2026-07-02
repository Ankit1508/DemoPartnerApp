package com.example.demopartnerapp.bridge

import android.util.Log
import org.json.JSONObject

/**
 * Parses the JSON envelope the PWA sends over the bridge.
 * Mirrors EkincarePwa's HandleHeaderFromScript. The PWA always posts:
 *   { "action": "methodName", "payload": { ... } }
 * (see index.html / native-bridge.js). `payload` may be an object, a string, or absent.
 */
object HandleHeaderFromScript {

    private const val TAG = "PwaBridge"

    /** Returns (action, rawPayloadJsonString). Payload is "" when absent. */
    fun handleScriptData(data: String): Pair<String, String> {
        return try {
            val json = JSONObject(data)
            val action = json.optString("action", "")
            // payload can be an object or a scalar — keep it as its JSON string form.
            val payload = when {
                json.isNull("payload") -> ""
                json.has("payload") -> json.get("payload").toString()
                else -> ""
            }
            action to payload
        } catch (e: Exception) {
            Log.e(TAG, "handleScriptData parse failed: ${e.message} for data=$data")
            "" to ""
        }
    }
}

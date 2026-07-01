package com.example.demopartnerapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.demopartnerapp.databinding.ActivityPartnerApiBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Model A — Partner REST API.
 * Step 1: GET /api/get-access-token with HTTP Basic (username:password) -> access_token (1h TTL).
 * Step 2: call any endpoint with `Authorization: Bearer <access_token>`.
 */
class PartnerApiActivity : AppCompatActivity() {

    private lateinit var b: ActivityPartnerApiBinding
    private val http = OkHttpClient()
    private var accessToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPartnerApiBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "Partner API"

        b.etApiBase.setText(BuildConfig.EKIN_API_BASE)
        b.etUsername.setText(BuildConfig.EKIN_API_USERNAME)
        b.etPassword.setText(BuildConfig.EKIN_API_PASSWORD)
        b.etSamplePath.setText("/api/providers")

        b.btnGetToken.setOnClickListener { getToken() }
        b.btnSampleCall.setOnClickListener { sampleCall() }
    }

    private fun base() = b.etApiBase.text.toString().trim().trimEnd('/')

    private fun getToken() {
        val user = b.etUsername.text.toString().trim()
        val pass = b.etPassword.text.toString().trim()
        if (base().isEmpty() || user.isEmpty() || pass.isEmpty()) {
            toast("base, username and password are required"); return
        }
        log("GET ${base()}/api/get-access-token  (Basic)\n…")

        val req = Request.Builder()
            .url("${base()}/api/get-access-token")
            .header("Authorization", Credentials.basic(user, pass))
            .get()
            .build()

        http.newCall(req).enqueue(uiCallback { code, body ->
            log("HTTP $code\n$body")
            runCatching { JSONObject(body).optString("access_token").ifEmpty { null } }
                .getOrNull()?.let {
                    accessToken = it
                    b.btnSampleCall.isEnabled = true
                    toast("Token acquired")
                }
        })
    }

    private fun sampleCall() {
        val token = accessToken ?: run { toast("Get a token first"); return }
        val path = b.etSamplePath.text.toString().trim().let { if (it.startsWith("/")) it else "/$it" }
        log("GET ${base()}$path  (Bearer)\n…")

        val req = Request.Builder()
            .url("${base()}$path")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        http.newCall(req).enqueue(uiCallback { code, body -> log("HTTP $code\n$body") })
    }

    private fun uiCallback(onResult: (Int, String) -> Unit) = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            runOnUiThread { log("Request failed: ${e.message}") }
        }
        override fun onResponse(call: Call, response: Response) {
            val body = response.body?.string().orEmpty()
            val code = response.code
            runOnUiThread { onResult(code, body) }
        }
    }

    private fun log(m: String) { b.tvOutput.text = m }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

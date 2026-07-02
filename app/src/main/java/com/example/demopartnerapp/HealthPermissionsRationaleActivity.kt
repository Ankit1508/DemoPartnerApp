package com.example.demopartnerapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Required by Health Connect: shown when the user taps the privacy-policy link
 * in the Health Connect permission dialog (ACTION_SHOW_PERMISSIONS_RATIONALE).
 * Health Connect won't surface the permission request without this handler.
 */
class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_rationale)
    }
}

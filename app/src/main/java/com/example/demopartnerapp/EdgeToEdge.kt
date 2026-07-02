package com.example.demopartnerapp

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Edge-to-edge inset handling, applied consistently across the app.
 *
 * targetSdk 35+ forces edge-to-edge (content draws behind the status/nav bars),
 * so every screen must pad itself out of the system bars — otherwise content is
 * hidden under them, exactly like the ekincare PWA app does. IME insets are
 * folded into the bottom padding so form fields stay above the keyboard on all
 * devices (gesture-nav, 3-button-nav, cutouts).
 *
 * Call once after setContentView, passing the activity's root view.
 */
fun AppCompatActivity.applyEdgeToEdgeInsets(root: View) {
    // Light background -> dark status bar icons so they stay visible.
    WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true

    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        insets
    }
}

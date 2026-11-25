package com.kokouliss.vaultanything

import android.app.Application
import com.kokouliss.vaultanything.feature.auth.restoreNightMode
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VaultAnythingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before any screen exists, so the first one already has the chosen light or dark look.
        restoreNightMode(this)
    }
}

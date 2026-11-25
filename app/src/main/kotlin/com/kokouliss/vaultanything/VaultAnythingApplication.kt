package com.kokouliss.vaultanything

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.kokouliss.vaultanything.feature.auth.restoreNightMode
import dagger.hilt.android.HiltAndroidApp

/** The channel of the reminders that firebase/functions sends. The manifest names it as well. */
internal const val REMINDERS_CHANNEL = "reminders"

@HiltAndroidApp
class VaultAnythingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before any screen exists, so the first one already has the chosen light or dark look.
        restoreNightMode(this)
        // Creating a channel that exists only refreshes its name, so this is safe on every start.
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(REMINDERS_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(R.string.channel_reminders))
                .build(),
        )
    }
}

package com.kokouliss.vaultanything

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.kokouliss.vaultanything.core.data.isBackendConfigured
import com.kokouliss.vaultanything.core.domain.model.InviteCode
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import dagger.hilt.android.AndroidEntryPoint

/** What the app was opened with, besides its icon. Each part is handled once and then cleared. */
internal data class Launch(
    /** A file, a link or text that another app shared. */
    val shared: Shared? = null,
    /** The code of a tapped or scanned invite link. */
    val inviteCode: String? = null,
)

internal data class Shared(val uri: String?, val text: String?)

// Everything in an intent comes from outside the app. It is only ever shown, parsed into a code or
// a web link, or uploaded as a file the person then names, which the security rules guard.
private fun Intent.toLaunch() = Launch(
    shared = if (action == Intent.ACTION_SEND) {
        Shared(
            uri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
            text = getStringExtra(Intent.EXTRA_TEXT),
        ).takeIf { it.uri != null || it.text != null }
    } else {
        null
    },
    inviteCode = if (action == Intent.ACTION_VIEW) dataString?.let(InviteCode::fromLink) else null,
)

// AppCompatActivity, because it is what applies the language chosen on the profile page on Android 9 to 12.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var launch by mutableStateOf(Launch())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not after a rotation: by then the screen it asked for is already open.
        if (savedInstanceState == null) launch = intent.toLaunch()
        // Checked before any screen asks Hilt for Firebase, which would crash without a configuration.
        val isConfigured = isBackendConfigured(this)
        setContent {
            VaultAnythingTheme {
                if (isConfigured) {
                    VaultAnythingApp(launch = launch, onLaunchHandled = { launch = Launch() })
                } else {
                    SetupNeededScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launch = intent.toLaunch()
    }
}

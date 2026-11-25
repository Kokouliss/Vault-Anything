package com.kokouliss.vaultanything.core.ui.component

import android.app.Activity
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private const val WAYS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/** Whether the phone can confirm it is the person: a fingerprint, their face, or the screen lock. */
fun canUseDeviceLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(WAYS) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Asks the phone to confirm it is the person. [onResult] gets true when it did, false when they
 * gave up or the phone could not; the caller then falls back to the PIN. The prompt needs a
 * FragmentActivity, which MainActivity is.
 */
fun Activity.confirmWithDeviceLock(title: String, onResult: (Boolean) -> Unit) {
    if (this !is FragmentActivity) return onResult(false)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
    }
    BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        .authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(WAYS).build())
}

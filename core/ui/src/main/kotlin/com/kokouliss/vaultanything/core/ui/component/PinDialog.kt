package com.kokouliss.vaultanything.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.kokouliss.vaultanything.core.domain.repository.PIN_MAX_LENGTH
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import com.kokouliss.vaultanything.core.domain.repository.isValidPin
import com.kokouliss.vaultanything.core.ui.R

/**
 * Asks for a PIN: digits, hidden as they are typed. [onConfirm] gets a valid one; a short one
 * is pointed out here. [result] from the last try shows under the field, [PinResult.Ok] and null show nothing.
 */
@Composable
fun PinDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.core_ui_unlock),
    result: PinResult? = null,
    isBusy: Boolean = false,
    supportingText: String = stringResource(R.string.core_ui_pin_hint),
) {
    var tooShort by rememberSaveable { mutableStateOf(false) }
    TextInputDialog(
        title = title,
        label = stringResource(R.string.core_ui_pin),
        confirmLabel = confirmLabel,
        onConfirm = { pin ->
            tooShort = !isValidPin(pin)
            if (!tooShort) onConfirm(pin)
        },
        onDismiss = onDismiss,
        maxLength = PIN_MAX_LENGTH,
        keyboardType = KeyboardType.NumberPassword,
        supportingText = supportingText,
        isBusy = isBusy,
        isPassword = true,
        errorText = when {
            tooShort -> stringResource(R.string.core_ui_pin_hint)
            else -> result?.label()?.let { stringResource(it) }
        },
    )
}

private fun PinResult.label(): Int? = when (this) {
    PinResult.Ok -> null
    PinResult.Wrong -> R.string.core_ui_pin_wrong
    PinResult.TooManyTries -> R.string.core_ui_pin_too_many_tries
    PinResult.NoPin -> R.string.core_ui_pin_no_pin
    PinResult.NeedsSignIn -> R.string.core_ui_pin_needs_sign_in
    PinResult.Failed -> R.string.core_ui_error_generic
}

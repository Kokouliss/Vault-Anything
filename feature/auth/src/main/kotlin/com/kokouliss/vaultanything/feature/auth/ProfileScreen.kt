package com.kokouliss.vaultanything.feature.auth

import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kokouliss.vaultanything.core.domain.model.EMERGENCY_WAIT_DAYS
import com.kokouliss.vaultanything.core.domain.model.Emergency
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Trust
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import com.kokouliss.vaultanything.core.domain.repository.UNLOCK_MINUTES
import com.kokouliss.vaultanything.core.ui.R as CoreR
import com.kokouliss.vaultanything.core.ui.component.AnyKindWordmark
import com.kokouliss.vaultanything.core.ui.component.AppIcon
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.BackButton
import com.kokouliss.vaultanything.core.ui.component.ConfirmDialog
import com.kokouliss.vaultanything.core.ui.component.InitialAvatar
import com.kokouliss.vaultanything.core.ui.component.PinDialog
import com.kokouliss.vaultanything.core.ui.component.TextInputDialog
import com.kokouliss.vaultanything.core.ui.component.pageWidth
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Language tag to the language's own name, which is not translated. Keep in step with the
 * values-xx folders and with app/src/main/res/xml/locales_config.xml.
 */
private val Languages = listOf("en" to "English", "el" to "Ελληνικά")

/** The tag that means "follow the phone's language". */
private const val SYSTEM_LANGUAGE = ""

/** The choices under Appearance: an AppCompatDelegate night mode and its label. */
private val NightModes = listOf(
    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.feature_auth_same_as_phone,
    AppCompatDelegate.MODE_NIGHT_NO to R.string.feature_auth_appearance_light,
    AppCompatDelegate.MODE_NIGHT_YES to R.string.feature_auth_appearance_dark,
)

@Composable
internal fun ProfileRoute(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
    nameViewModel: NameViewModel = hiltViewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val nameState by nameViewModel.state.collectAsStateWithLifecycle()
    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()
    val verificationSent by viewModel.verificationSent.collectAsStateWithLifecycle()
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    val emergency by viewModel.emergency.collectAsStateWithLifecycle()
    val trusting by viewModel.trusting.collectAsStateWithLifecycle()
    val adults by viewModel.adults.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    // Kept here as well: a choice that looks the same as before, such as Light on a light phone,
    // does not restart the activity.
    var nightMode by remember { mutableIntStateOf(AppCompatDelegate.getDefaultNightMode()) }
    ProfileScreen(
        user = user,
        // Not observed: choosing a language restarts the activity, which reads this again.
        languageTag = AppCompatDelegate.getApplicationLocales()[0]?.language ?: SYSTEM_LANGUAGE,
        nightMode = nightMode,
        onLanguageChange = { tag ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        },
        onNightModeChange = { mode ->
            nightMode = mode
            saveNightMode(context, mode)
        },
        onSignOut = viewModel::signOut,
        deleteState = deleteState,
        onDeleteAccount = {
            scope.launch {
                // Google's account sheet stands in for "are you sure it is you": Firebase wants a
                // fresh sign-in before it deletes an account.
                val result = activity?.let { requestGoogleIdToken(it) }
                if (result != GoogleSignInResult.Cancelled) viewModel.deleteAccount((result as? GoogleSignInResult.Success)?.idToken)
            }
        },
        onDeleteWithPassword = viewModel::deleteAccountWithPassword,
        verificationSent = verificationSent,
        onSendVerification = viewModel::sendVerificationEmail,
        onBack = onBack,
        onChangeName = nameViewModel::open,
        hasPin = hasPin,
        onSetPin = viewModel::setPin,
        onRemovePin = viewModel::removePin,
        // The same proof as for deleting the account: Google's account sheet, or the password below.
        onConfirmIdentity = { viewModel.confirmIdentity(activity?.let { (requestGoogleIdToken(it) as? GoogleSignInResult.Success)?.idToken }) },
        onConfirmIdentityWithPassword = viewModel::confirmIdentityWithPassword,
        emergency = emergency,
        trusting = trusting,
        adults = adults,
        names = names,
        onSetEmergency = viewModel::setEmergency,
        onRemoveEmergency = viewModel::removeEmergency,
        onAskEmergency = viewModel::askEmergency,
        onOpenEmergency = viewModel::openEmergency,
    )
    NameDialog(nameState, user?.name, onSave = nameViewModel::save, onClose = nameViewModel::close)
}

@Composable
internal fun ProfileScreen(
    user: User?,
    languageTag: String,
    nightMode: Int,
    onLanguageChange: (String) -> Unit,
    onNightModeChange: (Int) -> Unit,
    onSignOut: () -> Unit,
    deleteState: DeleteState,
    onDeleteAccount: () -> Unit,
    onDeleteWithPassword: (String) -> Unit,
    verificationSent: Boolean,
    onSendVerification: () -> Unit,
    onBack: () -> Unit,
    onChangeName: () -> Unit = {},
    hasPin: Boolean = false,
    onSetPin: suspend (pin: String, current: String?) -> PinResult = { _, _ -> PinResult.Ok },
    onRemovePin: suspend (current: String) -> PinResult = { PinResult.Ok },
    onConfirmIdentity: suspend () -> Boolean = { true },
    onConfirmIdentityWithPassword: suspend (String) -> Boolean = { true },
    emergency: Emergency? = null,
    trusting: List<Trust> = emptyList(),
    adults: List<Member> = emptyList(),
    names: Map<String, String> = emptyMap(),
    onSetEmergency: (contactId: String, waitDays: Int) -> Unit = { _, _ -> },
    onRemoveEmergency: () -> Unit = {},
    onAskEmergency: (ownerId: String) -> Unit = {},
    onOpenEmergency: suspend (ownerId: String) -> Boolean = { true },
) {
    val title = stringResource(R.string.feature_auth_profile_title)
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var askPassword by rememberSaveable { mutableStateOf(false) }
    val usesPassword = user?.usesPassword == true
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).pageWidth().verticalScroll(rememberScrollState())) {
            val name = user?.name.orEmpty()
            ListItem(
                leadingContent = { InitialAvatar(name, size = 56.dp) },
                headlineContent = { Text(name, style = MaterialTheme.typography.titleLarge) },
                supportingContent = user?.email?.let { email -> { Text(email) } },
                trailingContent = { AppIcon(AppIcons.Edit, null) },
                modifier = Modifier.clickable(
                    enabled = user != null,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.feature_auth_change_name),
                    onClick = onChangeName,
                ),
            )
            // Invites by email only reach a confirmed address, so an unconfirmed one says so here.
            if (user != null && !user.emailVerified) {
                ListItem(
                    leadingContent = { AppIcon(AppIcons.Mail, null) },
                    headlineContent = { Text(stringResource(R.string.feature_auth_confirm_email)) },
                    supportingContent = {
                        Text(
                            if (verificationSent) stringResource(R.string.feature_auth_confirm_email_sent)
                            else stringResource(R.string.feature_auth_confirm_email_text, user.email.orEmpty()),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    },
                    modifier = Modifier.clickable(enabled = !verificationSent, role = Role.Button, onClick = onSendVerification),
                )
            }
            HorizontalDivider()

            SectionTitle(stringResource(R.string.feature_auth_appearance))
            Column(Modifier.selectableGroup()) {
                NightModes.forEach { (mode, label) ->
                    Choice(stringResource(label), selected = nightMode == mode, onClick = { onNightModeChange(mode) })
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(stringResource(R.string.feature_auth_language))
            Column(Modifier.selectableGroup()) {
                Choice(
                    label = stringResource(R.string.feature_auth_same_as_phone),
                    selected = languageTag == SYSTEM_LANGUAGE,
                    onClick = { onLanguageChange(SYSTEM_LANGUAGE) },
                )
                Languages.forEach { (tag, name) ->
                    Choice(label = name, selected = languageTag == tag, onClick = { onLanguageChange(tag) })
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(stringResource(R.string.feature_auth_pin_section))
            PinSection(hasPin, usesPassword, onSetPin, onRemovePin, onConfirmIdentity, onConfirmIdentityWithPassword)
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(stringResource(R.string.feature_auth_emergency_section))
            EmergencySection(emergency, trusting, adults, names, onSetEmergency, onRemoveEmergency, onAskEmergency, onOpenEmergency)
            HorizontalDivider(Modifier.padding(top = 8.dp))

            ListItem(
                leadingContent = { AppIcon(AppIcons.Logout, null) },
                headlineContent = { Text(stringResource(R.string.feature_auth_sign_out)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = { confirmSignOut = true }),
            )
            val deleting = deleteState == DeleteState.Deleting
            ListItem(
                leadingContent = { AppIcon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text(stringResource(R.string.feature_auth_delete_account), color = MaterialTheme.colorScheme.error) },
                supportingContent = if (deleteState == DeleteState.Failed) {
                    {
                        Text(
                            stringResource(
                                if (usesPassword) R.string.feature_auth_delete_account_failed_password
                                else R.string.feature_auth_delete_account_failed,
                            ),
                        )
                    }
                } else {
                    null
                },
                trailingContent = if (deleting) {
                    { CircularProgressIndicator(Modifier.size(24.dp)) }
                } else {
                    null
                },
                modifier = Modifier.clickable(enabled = !deleting, role = Role.Button, onClick = { confirmDelete = true }),
            )
            // The maker, said once to a screen reader: the wordmark alone would say only "AnyKind".
            val maker = stringResource(R.string.feature_auth_maker)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp)
                    .clearAndSetSemantics { contentDescription = maker },
            ) {
                AnyKindWordmark()
                Text(
                    maker,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.feature_auth_delete_account),
            text = stringResource(
                if (usesPassword) R.string.feature_auth_delete_account_confirm_text_password
                else R.string.feature_auth_delete_account_confirm_text,
            ),
            confirmLabel = stringResource(CoreR.string.core_ui_delete),
            onConfirm = {
                confirmDelete = false
                // Firebase wants a fresh proof that it is them: their password, or Google's account sheet.
                if (usesPassword) askPassword = true else onDeleteAccount()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (askPassword) {
        TextInputDialog(
            title = stringResource(R.string.feature_auth_confirm_its_you),
            label = stringResource(R.string.feature_auth_password),
            confirmLabel = stringResource(CoreR.string.core_ui_delete),
            onConfirm = { password ->
                askPassword = false
                onDeleteWithPassword(password)
            },
            onDismiss = { askPassword = false },
            maxLength = 128,
            isPassword = true,
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.feature_auth_sign_out),
            text = stringResource(R.string.feature_auth_sign_out_confirm_text),
            confirmLabel = stringResource(R.string.feature_auth_sign_out),
            onConfirm = {
                confirmSignOut = false
                onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** Which dialog of the PIN section is open. */
private enum class PinStep { None, New, Current, CurrentToRemove, Password }

/**
 * Set, change, remove or replace the PIN that locks documents. Changing or removing takes the
 * current PIN; a forgotten one is replaced after the person confirms it is them, the way deleting
 * the account asks, which the server takes as a fresh sign-in for a few minutes.
 */
@Composable
private fun PinSection(
    hasPin: Boolean,
    usesPassword: Boolean,
    onSetPin: suspend (pin: String, current: String?) -> PinResult,
    onRemovePin: suspend (current: String) -> PinResult,
    onConfirmIdentity: suspend () -> Boolean,
    onConfirmIdentityWithPassword: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(PinStep.None) }
    /** The current PIN once given, for the new one that follows. */
    var current by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PinResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmFailed by rememberSaveable { mutableStateOf(false) }
    val close = {
        step = PinStep.None
        current = null
        result = null
    }
    // One call at a time; the dialog shows what came of it and closes once it worked.
    val attempt = { call: suspend () -> PinResult ->
        scope.launch {
            busy = true
            result = call()
            busy = false
            if (result == PinResult.Ok) close()
        }
    }
    val confirm = { call: suspend () -> Boolean ->
        scope.launch {
            busy = true
            val confirmed = call()
            busy = false
            confirmFailed = !confirmed
            step = if (confirmed) PinStep.New else PinStep.None
        }
    }

    if (!hasPin) {
        ListItem(
            leadingContent = { AppIcon(AppIcons.Lock, null) },
            headlineContent = { Text(stringResource(R.string.feature_auth_set_pin)) },
            supportingContent = { Text(stringResource(R.string.feature_auth_set_pin_hint)) },
            modifier = Modifier.clickable(role = Role.Button) { step = PinStep.New },
        )
    } else {
        ListItem(
            leadingContent = { AppIcon(AppIcons.Lock, null) },
            headlineContent = { Text(stringResource(R.string.feature_auth_change_pin)) },
            modifier = Modifier.clickable(role = Role.Button) { step = PinStep.Current },
        )
        ListItem(
            leadingContent = { AppIcon(AppIcons.LockOpen, null) },
            headlineContent = { Text(stringResource(R.string.feature_auth_remove_pin)) },
            supportingContent = { Text(stringResource(R.string.feature_auth_remove_pin_hint)) },
            modifier = Modifier.clickable(role = Role.Button) { step = PinStep.CurrentToRemove },
        )
        ListItem(
            leadingContent = { AppIcon(AppIcons.Refresh, null) },
            headlineContent = { Text(stringResource(R.string.feature_auth_forgot_pin)) },
            supportingContent = {
                Text(
                    stringResource(
                        when {
                            !confirmFailed -> R.string.feature_auth_forgot_pin_hint
                            usesPassword -> R.string.feature_auth_confirm_failed_password
                            else -> R.string.feature_auth_confirm_failed
                        },
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            trailingContent = if (busy && step == PinStep.None) {
                { CircularProgressIndicator(Modifier.size(24.dp)) }
            } else {
                null
            },
            modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
                confirmFailed = false
                if (usesPassword) step = PinStep.Password else confirm(onConfirmIdentity)
            },
        )
    }

    when (step) {
        PinStep.None -> Unit
        PinStep.New -> PinDialog(
            title = stringResource(if (hasPin) R.string.feature_auth_new_pin else R.string.feature_auth_set_pin),
            confirmLabel = stringResource(CoreR.string.core_ui_save),
            result = result,
            isBusy = busy,
            onConfirm = { pin -> attempt { onSetPin(pin, current) } },
            onDismiss = close,
        )
        PinStep.Current -> PinDialog(
            title = stringResource(R.string.feature_auth_current_pin),
            confirmLabel = stringResource(R.string.feature_auth_next),
            onConfirm = { pin ->
                current = pin
                step = PinStep.New
            },
            onDismiss = close,
        )
        PinStep.CurrentToRemove -> PinDialog(
            title = stringResource(R.string.feature_auth_current_pin),
            confirmLabel = stringResource(R.string.feature_auth_remove),
            result = result,
            isBusy = busy,
            onConfirm = { pin -> attempt { onRemovePin(pin) } },
            onDismiss = close,
        )
        PinStep.Password -> TextInputDialog(
            title = stringResource(R.string.feature_auth_confirm_its_you),
            label = stringResource(R.string.feature_auth_password),
            confirmLabel = stringResource(R.string.feature_auth_next),
            onConfirm = { password -> confirm { onConfirmIdentityWithPassword(password) } },
            onDismiss = close,
            maxLength = 128,
            isBusy = busy,
            isPassword = true,
        )
    }
}

/** Emergency access: whom the person trusts with their locked documents, and who trusts them. */
@Composable
private fun EmergencySection(
    emergency: Emergency?,
    trusting: List<Trust>,
    adults: List<Member>,
    names: Map<String, String>,
    onSet: (contactId: String, waitDays: Int) -> Unit,
    onRemove: () -> Unit,
    onAsk: (ownerId: String) -> Unit,
    onOpen: suspend (ownerId: String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var choosing by rememberSaveable { mutableStateOf(false) }
    /** What came of opening someone's documents, under their row. */
    var opened by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    val format = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    val date = { millis: Long -> format.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())) }
    val now = System.currentTimeMillis()

    Hint(stringResource(R.string.feature_auth_emergency_hint))
    if (emergency == null) {
        ListItem(
            leadingContent = { AppIcon(AppIcons.Person, null) },
            headlineContent = { Text(stringResource(R.string.feature_auth_emergency_choose)) },
            supportingContent = if (adults.isEmpty()) {
                { Text(stringResource(R.string.feature_auth_emergency_no_adults)) }
            } else {
                null
            },
            modifier = Modifier.clickable(enabled = adults.isNotEmpty(), role = Role.Button) { choosing = true },
        )
    } else {
        val opensAt = emergency.opensAtMillis
        ListItem(
            leadingContent = { AppIcon(AppIcons.Person, null) },
            headlineContent = { Text(names[emergency.contactId] ?: stringResource(R.string.feature_auth_emergency_someone)) },
            supportingContent = {
                Text(
                    when {
                        opensAt == null -> stringResource(R.string.feature_auth_emergency_wait, days(emergency.waitDays))
                        opensAt > now -> stringResource(R.string.feature_auth_emergency_asked, date(opensAt))
                        else -> stringResource(R.string.feature_auth_emergency_open_to_them)
                    },
                )
            },
            trailingContent = if (opensAt != null) {
                {
                    // Writing the choice again takes the request back; the server opens nothing after that.
                    TextButton(onClick = { onSet(emergency.contactId, emergency.waitDays) }) {
                        Text(stringResource(if (opensAt > now) R.string.feature_auth_say_no else R.string.feature_auth_stop))
                    }
                }
            } else {
                null
            },
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.feature_auth_emergency_change)) { choosing = true },
        )
    }
    if (trusting.isNotEmpty()) {
        Hint(stringResource(R.string.feature_auth_trusting_hint))
        trusting.forEach { trust ->
            val opensAt = trust.emergency.opensAtMillis
            val ready = opensAt != null && opensAt <= now
            ListItem(
                leadingContent = { AppIcon(AppIcons.LockOpen, null) },
                headlineContent = { Text(names[trust.ownerId].orEmpty()) },
                supportingContent = {
                    Text(
                        when {
                            opened[trust.ownerId] == true -> stringResource(R.string.feature_auth_trusting_opened, UNLOCK_MINUTES)
                            opened[trust.ownerId] == false -> stringResource(R.string.feature_auth_trusting_failed)
                            opensAt == null -> stringResource(R.string.feature_auth_trusting_wait, days(trust.emergency.waitDays))
                            !ready -> stringResource(R.string.feature_auth_trusting_asked, date(opensAt))
                            else -> stringResource(R.string.feature_auth_trusting_ready)
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                },
                trailingContent = when {
                    opensAt == null -> {
                        { TextButton(onClick = { onAsk(trust.ownerId) }) { Text(stringResource(R.string.feature_auth_ask)) } }
                    }
                    ready -> {
                        {
                            TextButton(onClick = { scope.launch { opened = opened + (trust.ownerId to onOpen(trust.ownerId)) } }) {
                                Text(stringResource(R.string.feature_auth_open))
                            }
                        }
                    }
                    else -> null
                },
            )
        }
    }
    if (choosing) {
        EmergencyDialog(
            current = emergency,
            adults = adults,
            onSet = { contactId, waitDays ->
                onSet(contactId, waitDays)
                choosing = false
            },
            onRemove = {
                onRemove()
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun EmergencyDialog(
    current: Emergency?,
    adults: List<Member>,
    onSet: (contactId: String, waitDays: Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var contactId by rememberSaveable { mutableStateOf(current?.contactId ?: adults.firstOrNull()?.id) }
    var waitDays by rememberSaveable { mutableIntStateOf(current?.waitDays ?: EMERGENCY_WAIT_DAYS[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_auth_emergency_choose)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup()) {
                    adults.forEach { adult -> Choice(adult.name, selected = contactId == adult.id, onClick = { contactId = adult.id }) }
                }
                Text(
                    stringResource(R.string.feature_auth_emergency_wait_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EMERGENCY_WAIT_DAYS.forEach { wait ->
                        FilterChip(selected = waitDays == wait, onClick = { waitDays = wait }, label = { Text(days(wait)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { contactId?.let { onSet(it, waitDays) } }, enabled = contactId != null) {
                Text(stringResource(CoreR.string.core_ui_save))
            }
        },
        dismissButton = {
            if (current != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.feature_auth_remove)) }
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.core_ui_cancel)) }
        },
    )
}

@Composable
private fun days(count: Int): String = pluralStringResource(R.plurals.feature_auth_days, count, count)

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** One radio row of a section. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole row is the control. A second click target inside it would be read out twice.
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

@PreviewLightDark
@Composable
private fun ProfileScreenPreview() {
    VaultAnythingTheme {
        ProfileScreen(
            user = User("u1", "Panos", "panos@example.com"),
            languageTag = "el",
            nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            onLanguageChange = {},
            onNightModeChange = {},
            onSignOut = {},
            deleteState = DeleteState.Idle,
            onDeleteAccount = {},
            onDeleteWithPassword = {},
            verificationSent = false,
            onSendVerification = {},
            onBack = {},
        )
    }
}

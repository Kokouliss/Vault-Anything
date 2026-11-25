package com.kokouliss.vaultanything.feature.documents

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.MAX_TITLE_LENGTH
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.ui.R as CoreR
import com.kokouliss.vaultanything.core.ui.component.AppIcon
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.BackButton
import com.kokouliss.vaultanything.core.ui.component.LoadingCrossfade
import com.kokouliss.vaultanything.core.ui.component.label
import com.kokouliss.vaultanything.core.ui.component.pageWidth
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MILLIS_PER_DAY = 86_400_000L

/** Pages in one scan. A thicker contract is better kept as the PDF its sender mailed. */
private const val MAX_SCAN_PAGES = 20

@Composable
internal fun EditDocumentRoute(viewModel: EditDocumentViewModel, onBack: () -> Unit, onSaved: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.savedId) { if (uiState.savedId != null) onSaved() }

    // The camera writes here; the file is uploaded from here, so one file is enough.
    val captureUri = remember { captureUri(context) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) viewModel.setFile(captureUri.toString(), "photo.jpg", readTitle = true)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setFile(uri.toString(), context.displayName(uri))
    }
    val activity = LocalActivity.current
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri?.let { viewModel.setFile(it.toString(), "scan.pdf", readTitle = true) }
    }
    // A shared file comes without its name; the picker's name is looked up the same way.
    LaunchedEffect(uiState.attachment) {
        val file = uiState.attachment as? Attachment.File
        if (file != null && file.name.isEmpty()) viewModel.setFile(file.uri, context.displayName(Uri.parse(file.uri)))
    }

    EditDocumentScreen(
        uiState = uiState,
        onBack = onBack,
        onScan = { activity?.let { startScan(it, scanner::launch) } },
        onTakePhoto = { camera.launch(captureUri) },
        onChooseFile = { picker.launch(arrayOf("*/*")) },
        onPasteLink = { viewModel.setLink("") },
        onClearAttachment = viewModel::clearAttachment,
        onTitleChange = viewModel::setTitle,
        onLinkChange = viewModel::setLink,
        onCategoryChange = viewModel::setCategory,
        onTagsChange = viewModel::setTags,
        onExpiresDayChange = viewModel::setExpiresDay,
        onNoteChange = viewModel::setNote,
        onFavoriteChange = viewModel::setFavorite,
        onOwnerChange = viewModel::setOwner,
        onSave = viewModel::save,
    )
}

/** Opens Google's scanner, which finds the edges of each page and hands back one PDF of them all. */
private fun startScan(activity: Activity, onReady: (IntentSenderRequest) -> Unit) {
    val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(MAX_SCAN_PAGES)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
        // The full mode also wipes shadows and a finger off the page, and gets Google's later improvements.
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()
    GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
        .addOnSuccessListener { onReady(IntentSenderRequest.Builder(it).build()) }
        // The scanner lives in Google Play services, not in the app, so a phone without them has none.
        .addOnFailureListener { Toast.makeText(activity, R.string.feature_documents_scan_unavailable, Toast.LENGTH_LONG).show() }
}

/** Where the camera app writes a new picture. */
private fun captureUri(context: Context): Uri {
    val file = File(context.cacheDir, "camera/capture.jpg").apply { parentFile?.mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** What the picker calls the file, or "file" when it does not say. */
private fun Context.displayName(uri: Uri): String {
    val name = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    }
    return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditDocumentScreen(
    uiState: EditDocumentUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFile: () -> Unit,
    onPasteLink: () -> Unit,
    onClearAttachment: () -> Unit,
    onTitleChange: (String) -> Unit,
    onLinkChange: (String) -> Unit,
    onCategoryChange: (DocumentCategory) -> Unit,
    onTagsChange: (String) -> Unit,
    onExpiresDayChange: (Long?) -> Unit,
    onNoteChange: (String) -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onOwnerChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val title = stringResource(if (uiState.isNew) R.string.feature_documents_add_title else R.string.feature_documents_edit_title)
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = onSave, enabled = uiState.canSave) { Text(stringResource(CoreR.string.core_ui_save)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pageWidth()) {
            LoadingCrossfade(!uiState.isLoaded) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (uiState.isSaving) {
                        val label = stringResource(R.string.feature_documents_uploading)
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = label
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                    if (uiState.isNew) {
                        AttachmentPicker(uiState, onScan, onTakePhoto, onChooseFile, onPasteLink, onClearAttachment, onLinkChange)
                    } else {
                        // What the document is stays as it was; only the words around it change.
                        val existing = uiState.existingFileName ?: (uiState.attachment as? Attachment.Link)?.text
                        if (existing != null) {
                            Text(existing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    uiState.error?.let { error ->
                        Text(
                            stringResource(
                                when (error) {
                                    SaveError.TooLarge -> R.string.feature_documents_too_large
                                    SaveError.Unreadable -> R.string.feature_documents_unreadable
                                    SaveError.Failed -> R.string.feature_documents_failed
                                },
                            ),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = onTitleChange,
                        label = { Text(stringResource(R.string.feature_documents_title_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        supportingText = if (uiState.title.length >= MAX_TITLE_LENGTH) {
                            { Text(stringResource(CoreR.string.core_ui_at_limit, MAX_TITLE_LENGTH)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (uiState.isNew && uiState.owners.size > 1) {
                        SectionTitle(stringResource(R.string.feature_documents_owner_label))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.owners.forEach { member ->
                                FilterChip(
                                    selected = member.id == uiState.ownerId,
                                    onClick = { onOwnerChange(member.id) },
                                    label = { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }

                    SectionTitle(stringResource(R.string.feature_documents_category_label))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DocumentCategory.entries.forEach { category ->
                            FilterChip(
                                selected = category == uiState.category,
                                onClick = { onCategoryChange(category) },
                                label = { Text(stringResource(category.label)) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.tags,
                        onValueChange = onTagsChange,
                        label = { Text(stringResource(R.string.feature_documents_tags_label)) },
                        supportingText = { Text(stringResource(R.string.feature_documents_tags_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // The date is picked, not typed, so the field only opens the picker.
                    val expiry = uiState.expiresDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)) {
                            AppIcon(AppIcons.Event, null, Modifier.padding(end = 8.dp))
                            Text(expiry ?: stringResource(R.string.feature_documents_expiry_none), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (expiry != null) {
                            IconButton(onClick = { onExpiresDayChange(null) }) {
                                AppIcon(AppIcons.Close, stringResource(R.string.feature_documents_clear_expiry))
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.note,
                        onValueChange = onNoteChange,
                        label = { Text(stringResource(R.string.feature_documents_note_label)) },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val favoriteLabel = stringResource(R.string.feature_documents_favorite_label)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(uiState.isFavorite, role = Role.Switch, onValueChange = onFavoriteChange)
                            .semantics { toggleableState = if (uiState.isFavorite) ToggleableState.On else ToggleableState.Off }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(favoriteLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = uiState.isFavorite, onCheckedChange = null)
                    }
                }
            }
        }
    }

    if (pickingDate) {
        // The picker counts in UTC days, which is also how the day is stored.
        val picked = rememberDatePickerState((uiState.expiresDay ?: LocalDate.now().toEpochDay()) * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picked.selectedDateMillis?.let { onExpiresDayChange(it / MILLIS_PER_DAY) }
                    pickingDate = false
                }) { Text(stringResource(CoreR.string.core_ui_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(CoreR.string.core_ui_cancel)) }
            },
        ) {
            DatePicker(picked)
        }
    }
}

/** A photo, a file or a link: the three ways a new document comes in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentPicker(
    uiState: EditDocumentUiState,
    onScan: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFile: () -> Unit,
    onPasteLink: () -> Unit,
    onClearAttachment: () -> Unit,
    onLinkChange: (String) -> Unit,
) {
    when (val attachment = uiState.attachment) {
        null -> {
            Text(stringResource(R.string.feature_documents_attachment_needed), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan) {
                    AppIcon(AppIcons.Document, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.feature_documents_scan_paper))
                }
                OutlinedButton(onClick = onTakePhoto) {
                    AppIcon(AppIcons.Camera, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.feature_documents_take_photo))
                }
                OutlinedButton(onClick = onChooseFile) {
                    AppIcon(AppIcons.AttachFile, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.feature_documents_choose_file))
                }
                OutlinedButton(onClick = onPasteLink) {
                    AppIcon(AppIcons.Link, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.feature_documents_paste_link))
                }
            }
        }
        is Attachment.File -> Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.AttachFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                attachment.name,
                Modifier.weight(1f).padding(horizontal = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onClearAttachment, enabled = !uiState.isSaving) { Text(stringResource(R.string.feature_documents_change)) }
        }
        is Attachment.Link -> Row(verticalAlignment = Alignment.Top) {
            LinkField(uiState, onLinkChange, Modifier.weight(1f))
            TextButton(onClick = onClearAttachment, Modifier.padding(top = 8.dp), enabled = !uiState.isSaving) {
                Text(stringResource(R.string.feature_documents_change))
            }
        }
    }
}

@Composable
private fun LinkField(uiState: EditDocumentUiState, onLinkChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = (uiState.attachment as? Attachment.Link)?.text.orEmpty(),
        onValueChange = onLinkChange,
        label = { Text(stringResource(R.string.feature_documents_link_label)) },
        isError = uiState.linkError,
        supportingText = if (uiState.linkError) {
            { Text(stringResource(R.string.feature_documents_link_invalid)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@PreviewLightDark
@Composable
private fun EditDocumentScreenPreview() {
    VaultAnythingTheme {
        EditDocumentScreen(
            uiState = EditDocumentUiState(
                isLoaded = true,
                title = "Passport",
                attachment = Attachment.File("content://x", "passport.pdf"),
                category = DocumentCategory.IDENTITY,
                ownerId = "u1",
                owners = listOf(Member("u1", "Panos"), Member("u3", "Nikos")),
            ),
            onBack = {},
            onScan = {},
            onTakePhoto = {},
            onChooseFile = {},
            onPasteLink = {},
            onClearAttachment = {},
            onTitleChange = {},
            onLinkChange = {},
            onCategoryChange = {},
            onTagsChange = {},
            onExpiresDayChange = {},
            onNoteChange = {},
            onFavoriteChange = {},
            onOwnerChange = {},
            onSave = {},
        )
    }
}

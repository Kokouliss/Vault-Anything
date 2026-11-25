package com.kokouliss.vaultanything.feature.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.print.PrintHelper
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.ui.R as CoreR
import com.kokouliss.vaultanything.core.ui.component.AppIcon
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.BackButton
import com.kokouliss.vaultanything.core.ui.component.ConfirmDialog
import com.kokouliss.vaultanything.core.ui.component.LoadingCrossfade
import com.kokouliss.vaultanything.core.ui.component.destructiveMenuItemColors
import com.kokouliss.vaultanything.core.ui.component.expiryLabel
import com.kokouliss.vaultanything.core.ui.component.icon
import com.kokouliss.vaultanything.core.ui.component.label
import com.kokouliss.vaultanything.core.ui.component.pageWidth
import com.kokouliss.vaultanything.core.ui.component.rememberImageBitmap
import com.kokouliss.vaultanything.core.ui.component.rememberPaperScroll
import com.kokouliss.vaultanything.core.ui.component.ruledPaper
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** What is done with the file once it is on the phone. */
private enum class FileAction { Open, Share, Print }

@Composable
internal fun DocumentDetailRoute(
    viewModel: DocumentDetailViewModel,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onGone: (message: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }

    if (uiState is DocumentDetailUiState.Gone) {
        LaunchedEffect(Unit) { onGone(resources.getString(R.string.feature_documents_document_gone)) }
    }

    val toast = { message: Int -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    val withFile = { document: Document, action: FileAction ->
        when {
            document.kind == DocumentKind.LINK -> context.act(action, Uri.parse(document.link), "text/plain", document.title, toast)
            !document.isUploaded -> toast(R.string.feature_documents_still_uploading)
            fetching -> Unit
            else -> scope.launch {
                fetching = true
                val path = viewModel.localFile()
                fetching = false
                if (path == null) {
                    toast(R.string.feature_documents_could_not_fetch)
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
                    context.act(action, uri, document.mimeType ?: "application/octet-stream", document.title, toast, File(path))
                }
            }
        }
    }

    DocumentDetailScreen(
        uiState = uiState,
        fetching = fetching,
        onBack = onBack,
        onEdit = onEdit,
        onSetFavorite = viewModel::setFavorite,
        onDelete = {
            viewModel.delete()
            onBack()
        },
        onOpen = { withFile(it, FileAction.Open) },
        onShare = { withFile(it, FileAction.Share) },
        onPrint = { withFile(it, FileAction.Print) },
    )
}

/** Hands [uri] to another app, or to the printer. [file] is the same thing as a path, for printing a PDF. */
private fun Context.act(action: FileAction, uri: Uri, mimeType: String, title: String, toast: (Int) -> Unit, file: File? = null) {
    try {
        when (action) {
            FileAction.Open -> startActivity(
                if (mimeType == "text/plain") {
                    Intent(Intent.ACTION_VIEW, uri)
                } else {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            // Email, nearby phones, messaging apps and printers that take a share all sit in this sheet.
            FileAction.Share -> {
                val send = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_SUBJECT, title)
                if (mimeType == "text/plain") {
                    send.putExtra(Intent.EXTRA_TEXT, uri.toString())
                } else {
                    send.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, title))
            }
            FileAction.Print -> when {
                mimeType.startsWith("image/") -> PrintHelper(this).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(title, uri)
                file != null -> (getSystemService(Context.PRINT_SERVICE) as PrintManager).print(title, PdfPrintAdapter(title, file), null)
            }
        }
    } catch (e: ActivityNotFoundException) {
        toast(R.string.feature_documents_no_app)
    } catch (e: IOException) {
        // PrintHelper could not read the picture.
        toast(R.string.feature_documents_could_not_fetch)
    }
}

/** Sends a PDF to the printer as it is. The print system does the paging. */
private class PdfPrintAdapter(private val title: String, private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentDetailScreen(
    uiState: DocumentDetailUiState,
    fetching: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpen: (Document) -> Unit,
    onShare: (Document) -> Unit,
    onPrint: (Document) -> Unit,
) {
    val ready = uiState as? DocumentDetailUiState.Ready
    val document = ready?.document
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val title = document?.title.orEmpty()

    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (document != null && ready.canEdit) {
                        IconButton(onClick = { onSetFavorite(!document.isFavorite) }) {
                            AppIcon(
                                if (document.isFavorite) AppIcons.Star else AppIcons.StarOutline,
                                stringResource(if (document.isFavorite) R.string.feature_documents_unfavorite else R.string.feature_documents_favorite),
                                tint = if (document.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DocumentMenu(onEdit = onEdit, onDelete = { confirmDelete = true })
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .ruledPaper(MaterialTheme.colorScheme.primary, rememberPaperScroll())
                .padding(padding)
                .pageWidth(),
        ) {
            LoadingCrossfade(document == null) {
                if (document == null) return@LoadingCrossfade
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Preview(document)
                    if (fetching) {
                        val label = stringResource(R.string.feature_documents_fetching)
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = label
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                    // Wraps, so all three fit at a large font.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpen(document) }) {
                            AppIcon(AppIcons.OpenInNew, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.feature_documents_open))
                        }
                        OutlinedButton(onClick = { onShare(document) }) {
                            AppIcon(AppIcons.Share, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.feature_documents_share))
                        }
                        // Only pictures and PDFs print; other files go to the app that knows them.
                        if (document.mimeType?.startsWith("image/") == true || document.mimeType == "application/pdf") {
                            OutlinedButton(onClick = { onPrint(document) }) {
                                AppIcon(AppIcons.Print, null, Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.feature_documents_print))
                            }
                        }
                    }
                    Details(ready)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.feature_documents_delete_confirm_title),
            text = stringResource(R.string.feature_documents_delete_confirm_text),
            confirmLabel = stringResource(CoreR.string.core_ui_delete),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The picture of the document when it has one, else the icon of its kind on a card. */
@Composable
private fun Preview(document: Document) {
    val bitmap = rememberImageBitmap(document.thumbnail)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit,
                )
            } else {
                AppIcon(document.kind.icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(state: DocumentDetailUiState.Ready) {
    val document = state.document
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(document.details.category.label)) })
            document.details.tags.forEach { tag -> AssistChip(onClick = {}, enabled = false, label = { Text(tag) }) }
        }
        Line(stringResource(R.string.feature_documents_belongs_to, state.ownerName))
        document.daysLeft(state.today)?.let { days ->
            val date = LocalDate.ofEpochDay(document.details.expiresDay!!).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
            Line(
                stringResource(R.string.feature_documents_expires_on, date) + " · " + expiryLabel(days),
                emphasis = days <= 0,
            )
        }
        document.link?.let { Line(it) }
        document.fileName?.let { name ->
            val size = document.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
            Line(listOfNotNull(name, size).joinToString(" · "))
        }
        if (!document.isUploaded) Line(stringResource(R.string.feature_documents_still_uploading))
        document.details.note?.let { note ->
            Text(
                stringResource(R.string.feature_documents_note),
                Modifier.padding(top = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(note, style = MaterialTheme.typography.bodyLarge)
        }
        Line(stringResource(R.string.feature_documents_added_by, document.addedByName))
    }
}

@Composable
private fun Line(text: String, emphasis: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DocumentMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            AppIcon(AppIcons.MoreVert, stringResource(CoreR.string.core_ui_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_documents_edit)) },
                leadingIcon = { AppIcon(AppIcons.Edit, null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreR.string.core_ui_delete)) },
                leadingIcon = { AppIcon(AppIcons.Delete, null) },
                colors = destructiveMenuItemColors(),
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun DocumentDetailScreenPreview() {
    val today = 20_000L
    VaultAnythingTheme {
        DocumentDetailScreen(
            uiState = DocumentDetailUiState.Ready(
                document = Document(
                    id = "d1",
                    spaceId = "s1",
                    ownerId = "u1",
                    title = "Passport",
                    kind = DocumentKind.FILE,
                    details = DocumentDetails(DocumentCategory.IDENTITY, listOf("travel", "Panos"), today + 40, "Renew at the police station.", true),
                    fileName = "passport.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1_234_567,
                    addedByName = "Panos",
                ),
                ownerName = "Panos",
                canEdit = true,
                today = today,
            ),
            fetching = false,
            onBack = {},
            onEdit = {},
            onSetFavorite = {},
            onDelete = {},
            onOpen = {},
            onShare = {},
            onPrint = {},
        )
    }
}

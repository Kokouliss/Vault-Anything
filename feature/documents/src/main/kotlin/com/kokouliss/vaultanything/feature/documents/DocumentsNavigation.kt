package com.kokouliss.vaultanything.feature.documents

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** One document: its details and what can be done with it. */
@Serializable
data class DocumentDetailKey(val spaceId: String, val documentId: String) : NavKey

/**
 * Adds a document, or edits the one with [documentId]. A new one belongs to [ownerId], else to the
 * signed-in member. [sharedUri] and [sharedText] come from another app's share sheet.
 */
@Serializable
data class EditDocumentKey(
    val spaceId: String,
    val documentId: String? = null,
    val ownerId: String? = null,
    val sharedUri: String? = null,
    val sharedText: String? = null,
) : NavKey {
    val isNew: Boolean get() = documentId == null
    val fromShare: Boolean get() = sharedUri != null || sharedText != null
}

/** Something another app shared: asks which family space it goes to, when there is more than one. */
@Serializable
data class ShareKey(val uri: String?, val text: String?) : NavKey

fun EntryProviderScope<NavKey>.documentsEntries(
    onBack: () -> Unit,
    /** The document was deleted or access to it ended: the screen closes and [message] goes on the one below. */
    onGone: (message: String) -> Unit,
    onEdit: (spaceId: String, documentId: String) -> Unit,
    /** The shared content has a space: the editor takes the place of the "which space?" screen. */
    onShareInto: (spaceId: String, uri: String?, text: String?) -> Unit,
    /** A shared document was saved: the space takes the place of the editor. */
    onSharedSaved: (spaceId: String) -> Unit,
) {
    entry<DocumentDetailKey> { key ->
        DocumentDetailRoute(
            viewModel = hiltViewModel<DocumentDetailViewModel, DocumentDetailViewModel.Factory> { it.create(key) },
            onEdit = { onEdit(key.spaceId, key.documentId) },
            onBack = onBack,
            onGone = onGone,
        )
    }
    entry<EditDocumentKey> { key ->
        EditDocumentRoute(
            viewModel = hiltViewModel<EditDocumentViewModel, EditDocumentViewModel.Factory> { it.create(key) },
            onBack = onBack,
            onSaved = { if (key.fromShare) onSharedSaved(key.spaceId) else onBack() },
        )
    }
    entry<ShareKey> { key ->
        ShareRoute(onPickSpace = { spaceId -> onShareInto(spaceId, key.uri, key.text) }, onBack = onBack)
    }
}

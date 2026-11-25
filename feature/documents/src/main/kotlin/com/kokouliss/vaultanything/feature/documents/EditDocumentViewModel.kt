package com.kokouliss.vaultanything.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.MAX_NOTE_LENGTH
import com.kokouliss.vaultanything.core.domain.model.MAX_TITLE_LENGTH
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.normalizeLink
import com.kokouliss.vaultanything.core.domain.model.parseTags
import com.kokouliss.vaultanything.core.domain.repository.AddResult
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a new document is made of. An existing one keeps what it has. */
internal sealed interface Attachment {
    data class File(val uri: String, val name: String) : Attachment
    data class Link(val text: String) : Attachment {
        val normalized: String? get() = normalizeLink(text)
    }
}

internal enum class SaveError { TooLarge, Unreadable, Failed }

internal data class EditDocumentUiState(
    /** False until an existing document has arrived, or at once for a new one. */
    val isLoaded: Boolean = false,
    val isNew: Boolean = true,
    val title: String = "",
    val attachment: Attachment? = null,
    /** The file an existing document already has: shown, not changeable. */
    val existingFileName: String? = null,
    val category: DocumentCategory = DocumentCategory.OTHER,
    val tags: String = "",
    val expiresDay: Long? = null,
    val note: String = "",
    val isFavorite: Boolean = false,
    /** Whose document it will be, and who it could belong to. One choice means no picker. */
    val ownerId: String = "",
    val owners: List<Member> = emptyList(),
    val isSaving: Boolean = false,
    val error: SaveError? = null,
    /** Set once the document is saved; the screen then leaves. */
    val savedId: String? = null,
) {
    /** The link field explains itself once something is typed that is not a web address. */
    val linkError: Boolean get() = (attachment as? Attachment.Link)?.let { it.text.isNotBlank() && it.normalized == null } == true

    val canSave: Boolean
        get() = isLoaded && !isSaving && title.isNotBlank() && when {
            !isNew -> true
            attachment is Attachment.File -> true
            attachment is Attachment.Link -> attachment.normalized != null
            else -> false
        }
}

@HiltViewModel(assistedFactory = EditDocumentViewModel.Factory::class)
internal class EditDocumentViewModel @AssistedInject constructor(
    @Assisted private val key: EditDocumentKey,
    private val documentRepository: DocumentRepository,
    spaceRepository: SpaceRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val state = MutableStateFlow(EditDocumentUiState(isNew = key.isNew))
    val uiState: StateFlow<EditDocumentUiState> = state

    init {
        viewModelScope.launch {
            // Who it can belong to: the family for an adult, only themselves for a child.
            val (space, user) = combine(spaceRepository.observeSpace(key.spaceId), authRepository.currentUser) { s, u -> s to u }
                .first { (s, u) -> s != null && u != null }
            val owners = space!!.visibleMembers(user!!.id)
            val owner = key.ownerId?.takeIf { id -> owners.any { it.id == id } } ?: user.id
            if (key.documentId == null) {
                // Shared text is a link when it looks like one; otherwise it is a start for the title.
                val sharedLink = key.sharedText?.takeIf { normalizeLink(it) != null }
                state.update {
                    it.copy(
                        isLoaded = true,
                        owners = owners,
                        ownerId = owner,
                        attachment = when {
                            key.sharedUri != null -> Attachment.File(key.sharedUri, "")
                            sharedLink != null -> Attachment.Link(sharedLink)
                            else -> null
                        },
                        title = if (sharedLink == null) key.sharedText.orEmpty().trim().take(MAX_TITLE_LENGTH) else "",
                    )
                }
            } else {
                val document = documentRepository.observeDocument(key.spaceId, key.documentId).filterNotNull().first()
                state.update {
                    it.copy(
                        isLoaded = true,
                        owners = owners,
                        ownerId = document.ownerId,
                        title = document.title,
                        attachment = if (document.kind == DocumentKind.LINK) Attachment.Link(document.link.orEmpty()) else null,
                        existingFileName = document.fileName,
                        category = document.details.category,
                        tags = document.details.tags.joinToString(", "),
                        expiresDay = document.details.expiresDay,
                        note = document.details.note.orEmpty(),
                        isFavorite = document.isFavorite,
                    )
                }
            }
        }
    }

    fun setTitle(title: String) = state.update { it.copy(title = title.take(MAX_TITLE_LENGTH)) }

    /**
     * [name] is what the picker called the file; it becomes the title when there is none yet. With
     * [readTitle], for a scan or a photo whose name says nothing, the first line of words on it
     * takes that place a moment later, unless the person typed a title meanwhile.
     */
    fun setFile(uri: String, name: String, readTitle: Boolean = false) {
        val fromName = name.substringBeforeLast('.').take(MAX_TITLE_LENGTH)
        state.update { it.copy(attachment = Attachment.File(uri, name), title = it.title.ifBlank { fromName }, error = null) }
        if (!readTitle) return
        viewModelScope.launch {
            val suggested = documentRepository.suggestTitle(uri) ?: return@launch
            state.update { if (it.attachment == Attachment.File(uri, name) && it.title == fromName) it.copy(title = suggested) else it }
        }
    }

    fun setLink(text: String) = state.update { it.copy(attachment = Attachment.Link(text), error = null) }

    fun clearAttachment() = state.update { it.copy(attachment = null) }

    fun setCategory(category: DocumentCategory) = state.update { it.copy(category = category) }

    fun setTags(tags: String) = state.update { it.copy(tags = tags) }

    fun setExpiresDay(day: Long?) = state.update { it.copy(expiresDay = day) }

    fun setNote(note: String) = state.update { it.copy(note = note.take(MAX_NOTE_LENGTH)) }

    fun setFavorite(favorite: Boolean) = state.update { it.copy(isFavorite = favorite) }

    fun setOwner(ownerId: String) = state.update { it.copy(ownerId = ownerId) }

    fun save() {
        val form = state.value
        if (!form.canSave) return
        state.update { it.copy(isSaving = true, error = null) }
        val details = DocumentDetails(
            category = form.category,
            tags = parseTags(form.tags),
            expiresDay = form.expiresDay,
            note = form.note.trim().ifBlank { null },
            isFavorite = form.isFavorite,
        )
        val title = form.title.trim()
        viewModelScope.launch {
            val documentId = key.documentId
            val attachment = form.attachment
            when {
                documentId != null -> {
                    documentRepository.update(key.spaceId, documentId, title, details)
                    state.update { it.copy(isSaving = false, savedId = documentId) }
                }
                attachment is Attachment.Link -> {
                    val id = documentRepository.addLink(key.spaceId, form.ownerId, title, attachment.normalized!!, details)
                    state.update { it.copy(isSaving = false, savedId = id) }
                }
                attachment is Attachment.File -> {
                    val result = documentRepository.addFile(key.spaceId, form.ownerId, title, attachment.uri, details)
                    state.update {
                        when (result) {
                            is AddResult.Added -> it.copy(isSaving = false, savedId = result.documentId)
                            AddResult.TooLarge -> it.copy(isSaving = false, error = SaveError.TooLarge, attachment = null)
                            AddResult.Unreadable -> it.copy(isSaving = false, error = SaveError.Unreadable, attachment = null)
                            AddResult.Failed -> it.copy(isSaving = false, error = SaveError.Failed)
                        }
                    }
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(key: EditDocumentKey): EditDocumentViewModel
    }
}

package com.kokouliss.vaultanything.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface DocumentDetailUiState {
    data object Loading : DocumentDetailUiState

    /** Deleted, or this member may no longer see it. The screen closes itself. */
    data object Gone : DocumentDetailUiState

    data class Ready(
        val document: Document,
        /** Whose it is, as the family knows them. */
        val ownerName: String,
        /** Their own, or a child's seen by an adult. The security rules say the same. */
        val canEdit: Boolean,
        val today: Long = LocalDate.now().toEpochDay(),
    ) : DocumentDetailUiState
}

@HiltViewModel(assistedFactory = DocumentDetailViewModel.Factory::class)
internal class DocumentDetailViewModel @AssistedInject constructor(
    @Assisted private val key: DocumentDetailKey,
    private val documentRepository: DocumentRepository,
    spaceRepository: SpaceRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    val uiState: StateFlow<DocumentDetailUiState> = combine(
        documentRepository.observeDocument(key.spaceId, key.documentId),
        spaceRepository.observeSpace(key.spaceId),
        authRepository.currentUser,
    ) { document, space, user ->
        if (document == null || space == null || user == null) {
            DocumentDetailUiState.Gone
        } else {
            DocumentDetailUiState.Ready(
                document = document,
                ownerName = space.member(document.ownerId)?.name ?: document.addedByName,
                canEdit = space.canManageDocumentsOf(user.id, document.ownerId),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState.Loading)

    private val document: Document? get() = (uiState.value as? DocumentDetailUiState.Ready)?.document

    fun setFavorite(favorite: Boolean) {
        viewModelScope.launch { documentRepository.setFavorite(key.spaceId, key.documentId, favorite) }
    }

    fun delete() {
        val document = document ?: return
        viewModelScope.launch { documentRepository.delete(document) }
    }

    /** The file on this phone, fetched if need be. Null when it cannot be had right now. */
    suspend fun localFile(): String? = document?.let { documentRepository.fetchFile(it) }

    @AssistedFactory
    interface Factory {
        fun create(key: DocumentDetailKey): DocumentDetailViewModel
    }
}

package com.kokouliss.vaultanything.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.repository.ActivityRepository
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import com.kokouliss.vaultanything.core.domain.repository.LockRepository
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
        /** Their own: the only one who locks it. */
        val isOwner: Boolean = false,
        /** Locked and not opened on this phone yet: the screen asks for the PIN or the phone's lock instead. */
        val isSealed: Boolean = false,
        /** The owner, with a PIN to lock it behind. */
        val canLock: Boolean = false,
        val today: Long = LocalDate.now().toEpochDay(),
    ) : DocumentDetailUiState
}

@HiltViewModel(assistedFactory = DocumentDetailViewModel.Factory::class)
internal class DocumentDetailViewModel @AssistedInject constructor(
    @Assisted private val key: DocumentDetailKey,
    private val documentRepository: DocumentRepository,
    spaceRepository: SpaceRepository,
    authRepository: AuthRepository,
    private val lockRepository: LockRepository,
    activityRepository: ActivityRepository,
) : ViewModel() {

    val uiState: StateFlow<DocumentDetailUiState> = combine(
        documentRepository.observeDocument(key.spaceId, key.documentId),
        spaceRepository.observeSpace(key.spaceId),
        authRepository.currentUser,
        lockRepository.unlockedOwners,
        lockRepository.hasPin,
    ) { document, space, user, unlocked, hasPin ->
        if (document == null || space == null || user == null) {
            DocumentDetailUiState.Gone
        } else {
            val isOwner = document.ownerId == user.id
            DocumentDetailUiState.Ready(
                document = document,
                ownerName = space.member(document.ownerId)?.name ?: document.addedByName,
                canEdit = space.canManageDocumentsOf(user.id, document.ownerId),
                isOwner = isOwner,
                // Someone else's locked document only gets here while the server lets them see it.
                isSealed = document.isLocked && isOwner && hasPin && user.id !in unlocked,
                canLock = isOwner && hasPin,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState.Loading)

    init {
        // Once per visit, for the activity log: looking at one's own documents is nobody's business.
        viewModelScope.launch {
            val state = uiState.filterIsInstance<DocumentDetailUiState.Ready>().first()
            if (!state.isOwner) activityRepository.logOpened(state.document)
        }
    }

    private val document: Document? get() = (uiState.value as? DocumentDetailUiState.Ready)?.document

    fun setFavorite(favorite: Boolean) {
        viewModelScope.launch { documentRepository.setFavorite(key.spaceId, key.documentId, favorite) }
    }

    fun setLocked(locked: Boolean) {
        val document = document ?: return
        viewModelScope.launch { documentRepository.setLocked(document, locked) }
    }

    /** Checks the owner's PIN with the server. Ok opens their locked documents on this phone for a while. */
    suspend fun unlock(pin: String): PinResult {
        val document = document ?: return PinResult.Failed
        return lockRepository.unlock(key.spaceId, document.ownerId, pin)
    }

    /** The phone confirmed it is the person: their own locked documents open. */
    fun unlockSelf() = lockRepository.unlockSelf()

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

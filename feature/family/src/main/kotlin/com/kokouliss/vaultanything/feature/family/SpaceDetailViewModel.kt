package com.kokouliss.vaultanything.feature.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.expiringSoon
import com.kokouliss.vaultanything.core.domain.model.matches
import com.kokouliss.vaultanything.core.domain.model.normalizeEmail
import com.kokouliss.vaultanything.core.domain.model.sortedForDisplay
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import com.kokouliss.vaultanything.core.ui.component.Undoable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which documents the list shows. Nothing set means every document this member may see. */
internal data class DocumentFilter(
    /** One member's documents, or everyone's. */
    val memberId: String? = null,
    val category: DocumentCategory? = null,
    val favoritesOnly: Boolean = false,
    /** Words to look for in titles, tags, notes and the text read off scans. */
    val query: String = "",
)

internal sealed interface SpaceDetailUiState {
    data object Loading : SpaceDetailUiState

    /** The space was deleted, or this user left or was removed. The screen closes itself. */
    data object Gone : SpaceDetailUiState

    data class Ready(
        val space: Space,
        val currentUserId: String,
        /** Every document this member may see: all of them for an adult, their own for a child. */
        val allDocuments: List<Document>,
        val filter: DocumentFilter = DocumentFilter(),
        /** Days since 1970, so "expires in 12 days" is counted from the same day everywhere. */
        val today: Long = LocalDate.now().toEpochDay(),
    ) : SpaceDetailUiState {
        val isOwner: Boolean get() = space.ownerId == currentUserId
        val isAdult: Boolean get() = space.isAdult(currentUserId)

        /** The members whose documents this member may look at: the family for an adult, themselves for a child. */
        val visibleMembers get() = space.visibleMembers(currentUserId)

        /** The documents the filter leaves, favorites first. */
        val documents: List<Document>
            get() = allDocuments.filter { document ->
                (filter.memberId == null || document.ownerId == filter.memberId) &&
                    (filter.category == null || document.details.category == filter.category) &&
                    (!filter.favoritesOnly || document.isFavorite) &&
                    document.matches(filter.query)
            }.sortedForDisplay()

        /** Documents that run out within three months, soonest first, whatever the filter says. */
        val expiring: List<Document> get() = allDocuments.expiringSoon(today)

        /** Whose documents an added one belongs to: the member being looked at, else the signed-in one. */
        val addFor: String get() = filter.memberId ?: currentUserId
    }
}

@HiltViewModel(assistedFactory = SpaceDetailViewModel.Factory::class)
internal class SpaceDetailViewModel @AssistedInject constructor(
    @Assisted private val key: SpaceDetailKey,
    private val spaceRepository: SpaceRepository,
    documentRepository: DocumentRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(DocumentFilter())

    /** Email invites taken off the list at once and cancelled only once their Undo snackbar is gone. */
    val cancelledInvites = Undoable<String>(viewModelScope) { spaceRepository.cancelEmailInvite(key.spaceId, it) }

    private val space = spaceRepository.observeSpace(key.spaceId)

    // The query depends on the role: a child may only ask for their own documents. It is sent again
    // only when the role changes, not on every change to the space.
    private val documents = combine(space, authRepository.currentUser) { space, user ->
        user?.id?.let { it to (space?.isAdult(it) ?: false) }
    }.distinctUntilChanged().flatMapLatest { member ->
        if (member == null) flowOf(emptyList()) else documentRepository.observeDocuments(key.spaceId, member.first, member.second)
    }

    val uiState: StateFlow<SpaceDetailUiState> = combine(
        space,
        documents,
        authRepository.currentUser,
        cancelledInvites.pending,
        filter,
    ) { space, documents, user, cancelled, filter ->
        if (space == null || user == null) {
            SpaceDetailUiState.Gone
        } else {
            val invited = space.invitedEmails.filter { it != cancelled?.removal }
            // A member who left takes their filter with them.
            val kept = if (filter.memberId != null && space.member(filter.memberId) == null) filter.copy(memberId = null) else filter
            SpaceDetailUiState.Ready(space.copy(invitedEmails = invited), user.id, documents, kept)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpaceDetailUiState.Loading)

    private val readySpace: Space? get() = (uiState.value as? SpaceDetailUiState.Ready)?.space

    fun showMember(memberId: String?) = filter.update { it.copy(memberId = memberId) }

    fun showCategory(category: DocumentCategory?) = filter.update { it.copy(category = category, favoritesOnly = false) }

    fun showFavorites() = filter.update { it.copy(category = null, favoritesOnly = true) }

    fun search(query: String) = filter.update { it.copy(query = query) }

    fun renameSpace(name: String) {
        viewModelScope.launch { spaceRepository.renameSpace(key.spaceId, name) }
    }

    /** The screen only enables sending for a valid address; the new invite then shows up in the pending list. */
    fun inviteEmail(input: String) {
        val email = normalizeEmail(input) ?: return
        viewModelScope.launch { spaceRepository.inviteEmail(key.spaceId, email) }
    }

    fun cancelEmailInvite(email: String) = cancelledInvites.remove(email)

    fun setRole(memberId: String, role: Role) {
        viewModelScope.launch { spaceRepository.setRole(key.spaceId, memberId, role) }
    }

    fun removeMember(memberId: String) {
        val space = readySpace ?: return
        viewModelScope.launch { spaceRepository.removeMember(space, memberId) }
    }

    fun resetInviteCode() {
        val space = readySpace ?: return
        viewModelScope.launch { spaceRepository.resetInviteCode(space) }
    }

    fun leaveSpace() {
        viewModelScope.launch { spaceRepository.leaveSpace(key.spaceId) }
    }

    fun deleteSpace() {
        val space = readySpace ?: return
        viewModelScope.launch { spaceRepository.deleteSpace(space) }
    }

    override fun onCleared() = cancelledInvites.flush()

    @AssistedFactory
    interface Factory {
        fun create(key: SpaceDetailKey): SpaceDetailViewModel
    }
}

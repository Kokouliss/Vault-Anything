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
import com.kokouliss.vaultanything.core.domain.repository.LockRepository
import com.kokouliss.vaultanything.core.domain.repository.PinResult
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
        /**
         * Every document this member sees right now: everyone's unlocked ones for an adult, their
         * own for a child, and the locked ones that were opened on this phone.
         */
        val allDocuments: List<Document>,
        val filter: DocumentFilter = DocumentFilter(),
        /** Days since 1970, so "expires in 12 days" is counted from the same day everywhere. */
        val today: Long = LocalDate.now().toEpochDay(),
        /** This member's own documents, the locked ones too, whether shown or not. */
        val ownDocuments: List<Document> = emptyList(),
        /** The members whose locked documents are open on this phone right now. */
        val unlockedOwners: Set<String> = emptySet(),
        val hasPin: Boolean = false,
    ) : SpaceDetailUiState {
        val isOwner: Boolean get() = space.ownerId == currentUserId
        val isAdult: Boolean get() = space.isAdult(currentUserId)

        val lockedOwnCount: Int get() = ownDocuments.count { it.isLocked }

        /** Own locked documents wait behind the PIN or the phone's lock. Without a PIN there is nothing to ask for. */
        val ownLockedHidden: Boolean get() = lockedOwnCount > 0 && hasPin && currentUserId !in unlockedOwners

        /** Nothing to show, and nothing waiting behind the PIN either. */
        val isEmpty: Boolean get() = allDocuments.isEmpty() && lockedOwnCount == 0

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
    private val documentRepository: DocumentRepository,
    authRepository: AuthRepository,
    private val lockRepository: LockRepository,
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

    // The locked documents of other members opened on this phone. The server sends them only while
    // the unlock lasts; their own locked documents come with the query above.
    private val lockedOfOthers = combine(authRepository.currentUser, lockRepository.unlockedOwners) { user, owners -> owners - setOfNotNull(user?.id) }
        .distinctUntilChanged()
        .flatMapLatest { owners ->
            if (owners.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(owners.map { documentRepository.observeLockedDocuments(key.spaceId, it) }) { lists -> lists.flatMap { it } }
            }
        }

    private class Content(val documents: List<Document>, val lockedOfOthers: List<Document>, val unlocked: Set<String>, val hasPin: Boolean)

    private val content = combine(documents, lockedOfOthers, lockRepository.unlockedOwners, lockRepository.hasPin) { d, l, u, h -> Content(d, l, u, h) }

    val uiState: StateFlow<SpaceDetailUiState> = combine(
        space,
        content,
        authRepository.currentUser,
        cancelledInvites.pending,
        filter,
    ) { space, content, user, cancelled, filter ->
        if (space == null || user == null) {
            SpaceDetailUiState.Gone
        } else {
            val invited = space.invitedEmails.filter { it != cancelled?.removal }
            // A member who left takes their filter with them.
            val kept = if (filter.memberId != null && space.member(filter.memberId) == null) filter.copy(memberId = null) else filter
            // A locked document shows once its owner's PIN was given on this phone. One's own show without a PIN to ask for.
            val shown = content.documents.filter { !it.isLocked || it.ownerId in content.unlocked || (it.ownerId == user.id && !content.hasPin) }
            SpaceDetailUiState.Ready(
                space = space.copy(invitedEmails = invited),
                currentUserId = user.id,
                allDocuments = (shown + content.lockedOfOthers).distinctBy { it.id },
                filter = kept,
                ownDocuments = content.documents.filter { it.ownerId == user.id },
                unlockedOwners = content.unlocked,
                hasPin = content.hasPin,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpaceDetailUiState.Loading)

    private val readySpace: Space? get() = (uiState.value as? SpaceDetailUiState.Ready)?.space

    fun showMember(memberId: String?) = filter.update { it.copy(memberId = memberId) }

    fun showCategory(category: DocumentCategory?) = filter.update { it.copy(category = category, favoritesOnly = false) }

    fun showFavorites() = filter.update { it.copy(category = null, favoritesOnly = true) }

    fun search(query: String) = filter.update { it.copy(query = query) }

    /** Puts every document of the signed-in member behind their PIN, or takes them all out. */
    fun setAllLocked(locked: Boolean) {
        val own = (uiState.value as? SpaceDetailUiState.Ready)?.ownDocuments ?: return
        viewModelScope.launch { own.filter { it.isLocked != locked }.forEach { documentRepository.setLocked(it, locked) } }
    }

    /** Checks [ownerId]'s PIN with the server. Ok shows their locked documents here for a while. */
    suspend fun unlock(ownerId: String, pin: String): PinResult = lockRepository.unlock(key.spaceId, ownerId, pin)

    /** The phone confirmed it is the person: their own locked documents show. */
    fun unlockSelf() = lockRepository.unlockSelf()

    /** An adult gives a child of the space a new PIN. */
    suspend fun resetPin(memberId: String, pin: String): PinResult = lockRepository.resetPin(key.spaceId, memberId, pin)

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

    fun setBirthday(memberId: String, day: Long?) {
        viewModelScope.launch { spaceRepository.setBirthday(key.spaceId, memberId, day) }
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

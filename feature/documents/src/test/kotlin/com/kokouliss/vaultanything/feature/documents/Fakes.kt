package com.kokouliss.vaultanything.feature.documents

import com.kokouliss.vaultanything.core.domain.model.ActivityEntry
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.Share
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.JoinResult
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.ActivityRepository
import com.kokouliss.vaultanything.core.domain.repository.AddResult
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import com.kokouliss.vaultanything.core.domain.repository.EmailAuthResult
import com.kokouliss.vaultanything.core.domain.repository.LockRepository
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class FakeAuthRepository(user: User = User("u1", "Panos", "panos@example.com")) : AuthRepository {
    override val currentUser: Flow<User?> = flowOf(user)
    override suspend fun signInWithGoogle(idToken: String) = true
    override suspend fun registerDevice() = Unit
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount(idToken: String) = true
    override suspend fun signInWithEmail(email: String, password: String) = EmailAuthResult.Success
    override suspend fun createAccount(name: String, email: String, password: String) = EmailAuthResult.Success
    override suspend fun sendPasswordReset(email: String) = EmailAuthResult.Success
    override suspend fun sendVerificationEmail() = true
    override suspend fun refreshEmailVerification() = Unit
    override suspend fun deleteAccountWithPassword(password: String) = true
    override suspend fun confirmIdentity(idToken: String) = true
    override suspend fun confirmIdentityWithPassword(password: String) = true
    override val asksForName: Flow<Boolean> = flowOf(false)
    override suspend fun setName(name: String) = true
    override fun keepName() = Unit
}

internal class FakeSpaceRepository(space: Space) : SpaceRepository {
    val space = MutableStateFlow<Space?>(space)
    override fun observeSpaces(): Flow<List<Space>> = space.map { listOfNotNull(it) }
    override fun observeInvitations(): Flow<List<Space>> = flowOf(emptyList())
    override fun observeSpace(spaceId: String): Flow<Space?> = space
    override suspend fun joinWithCode(code: String) = JoinResult.Joined
    override suspend fun createSpace(name: String) = Unit
    override suspend fun renameSpace(spaceId: String, name: String) = Unit
    override suspend fun acceptInvitation(spaceId: String) = Unit
    override suspend fun declineInvitation(spaceId: String) = Unit
    override suspend fun inviteEmail(spaceId: String, email: String) = Unit
    override suspend fun cancelEmailInvite(spaceId: String, email: String) = Unit
    override suspend fun setRole(spaceId: String, memberId: String, role: Role) = Unit
    override suspend fun removeMember(space: Space, memberId: String) = Unit
    override suspend fun resetInviteCode(space: Space) = Unit
    override suspend fun leaveSpace(spaceId: String) = Unit
    override suspend fun deleteSpace(space: Space) = Unit
}

internal class FakeDocumentRepository : DocumentRepository {
    val documents = MutableStateFlow(emptyList<Document>())
    var addFileResult: AddResult = AddResult.Added("new")
    val added = mutableListOf<Document>()
    val updated = mutableListOf<Pair<String, DocumentDetails>>()
    val favorites = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<String>()

    override fun observeDocuments(spaceId: String, userId: String, isAdult: Boolean): Flow<List<Document>> =
        documents.map { all -> if (isAdult) all else all.filter { it.ownerId == userId } }

    override fun observeLockedDocuments(spaceId: String, ownerId: String): Flow<List<Document>> =
        documents.map { all -> all.filter { it.ownerId == ownerId && it.isLocked } }

    override fun observeDocument(spaceId: String, documentId: String): Flow<Document?> =
        documents.map { all -> all.firstOrNull { it.id == documentId } }

    override suspend fun addLink(spaceId: String, ownerId: String, title: String, link: String, details: DocumentDetails): String {
        added += Document("new", spaceId, ownerId, title, DocumentKind.LINK, details, link = link)
        return "new"
    }

    override suspend fun addFile(spaceId: String, ownerId: String, title: String, sourceUri: String, details: DocumentDetails): AddResult {
        if (addFileResult is AddResult.Added) added += Document("new", spaceId, ownerId, title, DocumentKind.FILE, details, fileName = sourceUri)
        return addFileResult
    }

    override suspend fun update(spaceId: String, documentId: String, title: String, details: DocumentDetails) {
        updated += documentId to details
    }

    override suspend fun setFavorite(spaceId: String, documentId: String, favorite: Boolean) {
        favorites += documentId to favorite
    }

    override suspend fun setLocked(document: Document, locked: Boolean) {
        documents.value = documents.value.map { if (it.id == document.id) it.copy(isLocked = locked) else it }
    }

    override suspend fun suggestTitle(sourceUri: String): String? = null

    override suspend fun delete(document: Document) {
        deleted += document.id
    }

    override suspend fun fetchFile(document: Document): String? = null

    val shares = MutableStateFlow(emptyList<Share>())
    val kept = mutableSetOf<String>()

    override fun observeShares(document: Document): Flow<List<Share>> = shares.map { all -> all.filter { it.documentId == document.id } }

    override suspend fun share(document: Document, hours: Int): Share {
        val share = Share("sh${shares.value.size + 1}", document.id, "Me", hours * 3_600_000L, "https://example.com/s/x")
        shares.value += share
        return share
    }

    override suspend fun unshare(shareId: String): Boolean {
        shares.value = shares.value.filter { it.id != shareId }
        return true
    }

    override suspend fun isKept(document: Document) = document.id in kept

    override suspend fun setKept(document: Document, kept: Boolean): Boolean {
        if (kept) this.kept += document.id else this.kept -= document.id
        return true
    }
}

internal class FakeLockRepository(hasPin: Boolean = false) : LockRepository {
    override val hasPin = MutableStateFlow(hasPin)
    override val unlockedOwners = MutableStateFlow(emptySet<String>())
    var pin = "1234"

    override suspend fun setPin(pin: String, current: String?) = PinResult.Ok
    override suspend fun removePin(current: String?) = PinResult.Ok
    override suspend fun unlock(spaceId: String, ownerId: String, pin: String): PinResult {
        if (pin != this.pin) return PinResult.Wrong
        unlockedOwners.value += ownerId
        return PinResult.Ok
    }
    override fun unlockSelf() {
        unlockedOwners.value += "u1"
    }
    override suspend fun resetPin(spaceId: String, memberId: String, pin: String) = PinResult.Ok
}

internal class FakeActivityRepository : ActivityRepository {
    val opened = mutableListOf<String>()
    override fun observeActivity(spaceId: String): Flow<List<ActivityEntry>> = flowOf(emptyList())
    override suspend fun logOpened(document: Document) {
        opened += document.id
    }
}

package com.kokouliss.vaultanything.feature.family

import com.kokouliss.vaultanything.core.domain.model.JoinResult
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.EmailAuthResult
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Shared by the tests of this module: two files with the same private class would clash.
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
    override val asksForName: Flow<Boolean> = flowOf(false)
    override suspend fun setName(name: String) = true
    override fun keepName() = Unit
}

internal class FakeSpaceRepository(initial: Space? = null) : SpaceRepository {
    val spaces = MutableStateFlow(listOfNotNull(initial))
    val invitations = MutableStateFlow(emptyList<Space>())
    var failSpaces = false
    var joinResult = JoinResult.Joined
    val declined = mutableListOf<String>()

    override fun observeSpaces(): Flow<List<Space>> = flow {
        if (failSpaces) throw IOException("permission denied")
        emitAll(spaces)
    }

    override fun observeInvitations(): Flow<List<Space>> = invitations
    override fun observeSpace(spaceId: String): Flow<Space?> = spaces.map { all -> all.firstOrNull { it.id == spaceId } }
    override suspend fun joinWithCode(code: String) = joinResult

    override suspend fun createSpace(name: String) = Unit
    override suspend fun renameSpace(spaceId: String, name: String) = Unit
    override suspend fun acceptInvitation(spaceId: String) = Unit
    override suspend fun declineInvitation(spaceId: String) {
        declined += spaceId
    }
    override suspend fun inviteEmail(spaceId: String, email: String) = Unit
    override suspend fun cancelEmailInvite(spaceId: String, email: String) = Unit
    override suspend fun removeMember(space: Space, memberId: String) = Unit
    override suspend fun resetInviteCode(space: Space) = Unit
    override suspend fun setRole(spaceId: String, memberId: String, role: Role) = Unit
    override suspend fun leaveSpace(spaceId: String) = Unit
    override suspend fun deleteSpace(space: Space) = Unit
}

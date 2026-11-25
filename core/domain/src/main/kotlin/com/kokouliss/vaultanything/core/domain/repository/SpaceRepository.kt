package com.kokouliss.vaultanything.core.domain.repository

import com.kokouliss.vaultanything.core.domain.model.JoinResult
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import kotlinx.coroutines.flow.Flow

interface SpaceRepository {
    /** Spaces the signed-in user is a member of. */
    fun observeSpaces(): Flow<List<Space>>

    /** Spaces that invited the signed-in user's email address and are waiting for an answer. */
    fun observeInvitations(): Flow<List<Space>>

    /** Emits null once the space is deleted or the user is no longer a member. */
    fun observeSpace(spaceId: String): Flow<Space?>

    /** The signed-in user becomes its owner and first adult. */
    suspend fun createSpace(name: String)

    suspend fun renameSpace(spaceId: String, name: String)

    /** Needs a connection: a space cannot be read before its server accepts the new member. Joins as a child. */
    suspend fun joinWithCode(code: String): JoinResult

    /** Joins as a child. */
    suspend fun acceptInvitation(spaceId: String)

    suspend fun declineInvitation(spaceId: String)

    /** [email] must already be normalized with `normalizeEmail`. */
    suspend fun inviteEmail(spaceId: String, email: String)

    suspend fun cancelEmailInvite(spaceId: String, email: String)

    /** Adults only, and never for the owner, who is always an adult. */
    suspend fun setRole(spaceId: String, memberId: String, role: Role)

    /** Adults only, for another member. Null forgets it. The server makes the child an adult on their 18th birthday. */
    suspend fun setBirthday(spaceId: String, memberId: String, day: Long?)

    /** Owner only. Also resets the invite code so the removed person cannot rejoin with the old one. */
    suspend fun removeMember(space: Space, memberId: String)

    /** Owner only. The old code stops working. */
    suspend fun resetInviteCode(space: Space)

    suspend fun leaveSpace(spaceId: String)

    /** Owner only. Everything in it, documents and files, goes too. */
    suspend fun deleteSpace(space: Space)
}

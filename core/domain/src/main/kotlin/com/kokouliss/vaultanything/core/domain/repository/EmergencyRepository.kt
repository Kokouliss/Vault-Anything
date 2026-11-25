package com.kokouliss.vaultanything.core.domain.repository

import com.kokouliss.vaultanything.core.domain.model.Emergency
import com.kokouliss.vaultanything.core.domain.model.Trust
import kotlinx.coroutines.flow.Flow

/**
 * Emergency access to locked documents. The person names a contact and a wait on their profile;
 * asking and opening go through firebase/functions, which checks the wait and tells the person.
 */
interface EmergencyRepository {
    /** The signed-in person's own choice, or null without one. */
    val own: Flow<Emergency?>

    /** The people who named the signed-in person as their contact. */
    val trusting: Flow<List<Trust>>

    /** Names [contactId], or changes the wait. Also takes back a request that is waiting. */
    suspend fun set(contactId: String, waitDays: Int): Boolean

    suspend fun remove(): Boolean

    /** As the contact: asks for [ownerId]'s documents. The wait starts now and [ownerId] is told. */
    suspend fun ask(ownerId: String): Boolean

    /** As the contact, once the wait is over: opens [ownerId]'s locked documents for [UNLOCK_MINUTES]. */
    suspend fun open(ownerId: String): Boolean
}

package com.kokouliss.vaultanything.core.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val PIN_MIN_LENGTH = 4
const val PIN_MAX_LENGTH = 8

/** How long locked documents stay open after a PIN, on the phone and on the server alike. */
const val UNLOCK_MINUTES = 15L

/** Digits only, 4 to 8 of them. The server checks the same. */
fun isValidPin(pin: String): Boolean = pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it.isDigit() }

/** What came of a call about a PIN. */
enum class PinResult {
    Ok,
    Wrong,
    /** Five wrong tries in a row: the PIN is refused for a while. */
    TooManyTries,
    /** That person has no PIN, so nothing of theirs is locked. */
    NoPin,
    /** Changing or removing a PIN without the current one takes a sign-in from the last few minutes first. */
    NeedsSignIn,
    /** No connection, or the server refused. */
    Failed,
}

/**
 * The PIN that locks documents. It lives on the server only: every check is a call to
 * firebase/functions, which also lets an adult see the locked documents of another member for
 * [UNLOCK_MINUTES] after giving that member's PIN.
 */
interface LockRepository {
    /** Whether the signed-in person has a PIN. Without one there is nothing to lock with. */
    val hasPin: Flow<Boolean>

    /**
     * The members whose locked documents this phone shows right now: the person themselves after
     * their PIN or the phone's own lock, others after their PIN. Each for [UNLOCK_MINUTES].
     */
    val unlockedOwners: StateFlow<Set<String>>

    /**
     * Sets the PIN, or changes it. [current] is the one in use; null when there is none yet, or
     * right after [AuthRepository.confirmIdentity] for a forgotten one.
     */
    suspend fun setPin(pin: String, current: String?): PinResult

    /** As [setPin], but leaves the person without a PIN. Their locked documents stay locked until they open them. */
    suspend fun removePin(current: String?): PinResult

    /** Checks [ownerId]'s PIN. [PinResult.Ok] also opens their locked documents in [spaceId] for a while. */
    suspend fun unlock(spaceId: String, ownerId: String, pin: String): PinResult

    /** The phone confirmed it is the person (fingerprint, face or screen lock): their own locked documents open for a while. */
    fun unlockSelf()

    /** An adult gives [memberId], a child of [spaceId], a new PIN. */
    suspend fun resetPin(spaceId: String, memberId: String, pin: String): PinResult
}

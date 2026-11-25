package com.kokouliss.vaultanything.core.data.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kokouliss.vaultanything.core.data.di.ApplicationScope
import com.kokouliss.vaultanything.core.domain.repository.LockRepository
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import com.kokouliss.vaultanything.core.domain.repository.UNLOCK_MINUTES
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Talks to setPin, verifyPin and resetPin in firebase/functions. The PIN itself is never kept on the phone. */
@Singleton
internal class FirestoreLockRepository @Inject constructor(
    private val auth: FirebaseAuth,
    db: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    @ApplicationScope private val scope: CoroutineScope,
) : LockRepository {

    override val hasPin: Flow<Boolean> = auth.userFlow().flatMapLatest { user ->
        if (user == null) {
            flowOf(false)
        } else {
            db.collection(Collections.USERS).document(user.uid).snapshots()
                .map { it.getBoolean(Fields.HAS_PIN) == true }
                .catch { e -> if (e is FirebaseFirestoreException) emit(false) else throw e }
        }
    }

    /** Each unlocked owner with the time their unlock runs out. */
    private val until = MutableStateFlow(emptyMap<String, Long>())

    override val unlockedOwners: StateFlow<Set<String>> =
        until.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        // Whoever signs in next starts locked.
        scope.launch { auth.userFlow().collect { until.value = emptyMap() } }
    }

    override suspend fun setPin(pin: String, current: String?) = call("setPin", mapOf("pin" to pin, "current" to current))

    override suspend fun removePin(current: String?) = call("setPin", mapOf("pin" to null, "current" to current))

    override suspend fun unlock(spaceId: String, ownerId: String, pin: String): PinResult {
        val result = call("verifyPin", mapOf("spaceId" to spaceId, "ownerId" to ownerId, "pin" to pin))
        if (result == PinResult.Ok) open(ownerId)
        return result
    }

    override fun unlockSelf() {
        auth.currentUser?.uid?.let(::open)
    }

    override suspend fun resetPin(spaceId: String, memberId: String, pin: String) =
        call("resetPin", mapOf("spaceId" to spaceId, "memberId" to memberId, "pin" to pin))

    /** Shows [ownerId]'s locked documents for [UNLOCK_MINUTES], as after their PIN. */
    internal fun open(ownerId: String) {
        val runsOut = System.currentTimeMillis() + UNLOCK_MINUTES.minutes.inWholeMilliseconds
        until.update { it + (ownerId to runsOut) }
        scope.launch {
            delay(UNLOCK_MINUTES.minutes)
            // A later unlock of the same owner set a later time; leave that one be.
            until.update { if (it[ownerId] == runsOut) it - ownerId else it }
        }
    }

    private suspend fun call(name: String, data: Map<String, Any?>): PinResult = try {
        val response = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
        when (response?.get("result")) {
            "ok" -> PinResult.Ok
            "wrong" -> PinResult.Wrong
            "tooManyTries" -> PinResult.TooManyTries
            "noPin" -> PinResult.NoPin
            "needsSignIn" -> PinResult.NeedsSignIn
            else -> PinResult.Failed
        }
    } catch (e: FirebaseFunctionsException) {
        Log.w("VaultAnythingLock", "$name failed", e)
        PinResult.Failed
    }
}

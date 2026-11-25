package com.kokouliss.vaultanything.core.data.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kokouliss.vaultanything.core.domain.model.Emergency
import com.kokouliss.vaultanything.core.domain.model.Trust
import com.kokouliss.vaultanything.core.domain.repository.EmergencyRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** The person's choice lives in users/{uid}.emergency; asking and opening go through askEmergency and openEmergency in firebase/functions. */
@Singleton
internal class FirestoreEmergencyRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val locks: FirestoreLockRepository,
) : EmergencyRepository {

    private val users get() = db.collection(Collections.USERS)

    override val own: Flow<Emergency?> = auth.userFlow().flatMapLatest { user ->
        if (user == null) {
            flowOf(null)
        } else {
            users.document(user.uid).snapshots().map { it.toEmergency() }
                .catch { e -> if (e is FirebaseFirestoreException) emit(null) else throw e }
        }
    }

    override val trusting: Flow<List<Trust>> = auth.userFlow().flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else {
            // The one query the security rules allow on users: the documents that name the caller as contact.
            users.whereEqualTo("${Fields.EMERGENCY}.${Fields.CONTACT_ID}", user.uid).snapshots()
                .map { result -> result.documents.mapNotNull { it.toEmergency()?.let { emergency -> Trust(it.id, emergency) } } }
                .catch { e -> if (e is FirebaseFirestoreException) emit(emptyList()) else throw e }
        }
    }

    // The whole field each time, so a request that is waiting (askedAt) is taken back by any change.
    override suspend fun set(contactId: String, waitDays: Int): Boolean =
        write(mapOf(Fields.CONTACT_ID to contactId, Fields.WAIT_DAYS to waitDays, Fields.ASKED_AT to FieldValue.delete()))

    override suspend fun remove(): Boolean = write(FieldValue.delete())

    private suspend fun write(emergency: Any): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return try {
            users.document(uid).set(mapOf(Fields.EMERGENCY to emergency), SetOptions.merge()).await()
            true
        } catch (e: FirebaseFirestoreException) {
            Log.w("VaultAnythingEmergency", "Could not change the emergency contact", e)
            false
        }
    }

    override suspend fun ask(ownerId: String): Boolean = call("askEmergency", ownerId)

    override suspend fun open(ownerId: String): Boolean = call("openEmergency", ownerId).also { if (it) locks.open(ownerId) }

    private suspend fun call(name: String, ownerId: String): Boolean = try {
        functions.getHttpsCallable(name).call(mapOf("ownerId" to ownerId)).await()
        true
    } catch (e: FirebaseFunctionsException) {
        Log.w("VaultAnythingEmergency", "$name failed", e)
        false
    }
}

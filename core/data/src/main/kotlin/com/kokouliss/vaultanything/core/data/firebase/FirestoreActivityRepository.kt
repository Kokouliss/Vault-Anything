package com.kokouliss.vaultanything.core.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.kokouliss.vaultanything.core.domain.model.ActivityAction
import com.kokouliss.vaultanything.core.domain.model.ActivityEntry
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.repository.ActivityRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
internal class FirestoreActivityRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
) : ActivityRepository {

    override fun observeActivity(spaceId: String): Flow<List<ActivityEntry>> =
        activity(spaceId).orderBy(Fields.AT, Query.Direction.DESCENDING).limit(NEWEST)
            .snapshots()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toActivityEntry() } }
            // A child, or someone no longer in the space, is refused: nothing to show.
            .catch { e -> if (e is FirebaseFirestoreException) emit(emptyList()) else throw e }

    override suspend fun logOpened(document: Document) = log(document.spaceId, ActivityAction.OPENED, document)

    /** Written offline-first like the documents, so it is not awaited. */
    fun log(spaceId: String, action: ActivityAction, document: Document) {
        val user = auth.currentUser ?: return
        activity(spaceId).add(
            mapOf(
                Fields.AT to FieldValue.serverTimestamp(),
                Fields.ACTOR_ID to user.uid,
                Fields.ACTOR_NAME to user.memberName,
                Fields.ACTION to action.key,
                Fields.DOCUMENT_ID to document.id,
                Fields.DOCUMENT_TITLE to document.title,
            ),
        ).logFailure("log ${action.key}")
    }

    private fun activity(spaceId: String) =
        db.collection(Collections.SPACES).document(spaceId).collection(Collections.ACTIVITY)

    private companion object {
        // shortcut: the log is never trimmed and the screen shows the newest entries only; add a scheduled clean-up in firebase/functions when a family fills it.
        const val NEWEST = 200L
    }
}

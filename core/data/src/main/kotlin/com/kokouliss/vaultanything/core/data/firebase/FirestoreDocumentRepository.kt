package com.kokouliss.vaultanything.core.data.firebase

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.snapshots
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.storageMetadata
import com.kokouliss.vaultanything.core.data.di.ApplicationScope
import com.kokouliss.vaultanything.core.data.di.IoDispatcher
import com.kokouliss.vaultanything.core.domain.model.ActivityAction
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.MAX_FILE_BYTES
import com.kokouliss.vaultanything.core.domain.model.MAX_TITLE_LENGTH
import com.kokouliss.vaultanything.core.domain.model.Share
import com.kokouliss.vaultanything.core.domain.repository.AddResult
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal class FirestoreDocumentRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val thumbnails: Thumbnails,
    private val textReader: TextReader,
    private val activity: FirestoreActivityRepository,
    private val functions: FirebaseFunctions,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : DocumentRepository {

    override fun observeDocuments(spaceId: String, userId: String, isAdult: Boolean): Flow<List<Document>> {
        // The rules allow a child to list only their own documents, and an adult everyone's unlocked
        // ones, so the queries say so: a wider one is refused as a whole.
        val own = documents(spaceId).whereEqualTo(Fields.OWNER_ID, userId).listen(spaceId) { snapshot ->
            // A document from before locks existed has no field, which the rules treat as locked for
            // everyone else. Its owner's phone sets it straight.
            snapshot.documents.filter { !it.contains(Fields.LOCKED) }.forEach { it.reference.update(Fields.LOCKED, false).logFailure("mark unlocked") }
        }
        if (!isAdult) return own
        val unlocked = documents(spaceId).whereEqualTo(Fields.LOCKED, false).listen(spaceId)
        return combine(own, unlocked) { mine, theirs -> (mine + theirs).distinctBy { it.id } }
    }

    override fun observeLockedDocuments(spaceId: String, ownerId: String): Flow<List<Document>> =
        documents(spaceId).whereEqualTo(Fields.OWNER_ID, ownerId).whereEqualTo(Fields.LOCKED, true).listen(spaceId)

    /** The documents of [this] query as they change. [onSnapshot] sees each raw snapshot first. */
    private fun Query.listen(spaceId: String, onSnapshot: (QuerySnapshot) -> Unit = {}): Flow<List<Document>> =
        // With metadata changes, so a document says when its change has reached the cloud.
        snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                onSnapshot(snapshot)
                snapshot.documents.mapNotNull { it.toDocument(spaceId) }
            }
            .distinctUntilChanged()
            // Being removed from the space, made a child or an unlock running out ends the listener with "permission denied".
            .catch { e -> if (e is FirebaseFirestoreException) emit(emptyList()) else throw e }

    override fun observeDocument(spaceId: String, documentId: String): Flow<Document?> =
        documents(spaceId).document(documentId).snapshots(MetadataChanges.INCLUDE)
            .map { it.toDocument(spaceId) }
            .catch { e -> if (e is FirebaseFirestoreException) emit(null) else throw e }

    override suspend fun addLink(spaceId: String, ownerId: String, title: String, link: String, details: DocumentDetails): String {
        val document = documents(spaceId).document()
        document.set(newDocument(ownerId, title, DocumentKind.LINK, details) + (Fields.LINK to link)).logFailure("add link")
        activity.log(spaceId, ActivityAction.ADDED, Document(document.id, spaceId, ownerId, title, DocumentKind.LINK, details))
        return document.id
    }

    override suspend fun addFile(spaceId: String, ownerId: String, title: String, sourceUri: String, details: DocumentDetails): AddResult {
        val uri = Uri.parse(sourceUri)
        val picked = withContext(ioDispatcher) { describe(uri) } ?: return AddResult.Unreadable
        if (picked.sizeBytes > MAX_FILE_BYTES) return AddResult.TooLarge
        val kind = if (picked.mimeType.startsWith("image/")) DocumentKind.IMAGE else DocumentKind.FILE
        val thumbnail = withContext(ioDispatcher) { thumbnails.of(uri, picked.mimeType) }
        val document = documents(spaceId).document()
        val record = newDocument(ownerId, title, kind, details) + buildMap {
            put(Fields.FILE_NAME, picked.name)
            put(Fields.MIME_TYPE, picked.mimeType)
            put(Fields.SIZE_BYTES, picked.sizeBytes)
            put(Fields.UPLOADED, false)
            thumbnail?.let { put(Fields.THUMBNAIL, Blob.fromBytes(it)) }
        }
        // The upload runs in the application's scope, so leaving the screen does not cut it short.
        // The record is written and confirmed first: the Storage rules look it up to allow the upload.
        return applicationScope.async {
            try {
                document.set(record).await()
                storage.reference.child(filePath(spaceId, document.id))
                    .putFile(uri, storageMetadata { contentType = picked.mimeType })
                    .await()
                document.update(Fields.UPLOADED, true).logFailure("mark the file uploaded")
                activity.log(spaceId, ActivityAction.ADDED, Document(document.id, spaceId, ownerId, title, kind, details))
                // The words come later: reading them takes seconds the person need not wait for.
                applicationScope.launch {
                    textReader.of(uri, picked.mimeType)?.let { text -> document.update(Fields.TEXT, text).logFailure("save the words of the file") }
                }
                AddResult.Added(document.id)
            } catch (e: FirebaseFirestoreException) {
                AddResult.Failed
            } catch (e: StorageException) {
                // Nothing to keep: a record without its file is a document nobody can open.
                document.delete().logFailure("remove the record of a failed upload")
                AddResult.Failed
            } catch (e: IOException) {
                document.delete().logFailure("remove the record of a failed upload")
                AddResult.Unreadable
            }
        }.await()
    }

    override suspend fun update(spaceId: String, documentId: String, title: String, details: DocumentDetails) {
        documents(spaceId).document(documentId)
            .update(details.toMap() + mapOf(Fields.TITLE to title, Fields.UPDATED_AT to FieldValue.serverTimestamp()))
            .logFailure("edit document")
    }

    override suspend fun setFavorite(spaceId: String, documentId: String, favorite: Boolean) {
        documents(spaceId).document(documentId).update(Fields.FAVORITE, favorite).logFailure("mark favorite")
    }

    override suspend fun setLocked(document: Document, locked: Boolean) {
        documents(document.spaceId).document(document.id).update(Fields.LOCKED, locked).logFailure("lock document")
    }

    override suspend fun suggestTitle(sourceUri: String): String? {
        val uri = Uri.parse(sourceUri)
        return withContext(ioDispatcher) {
            val mimeType = describe(uri)?.mimeType ?: return@withContext null
            textReader.of(uri, mimeType)?.lineSequence()
                .orEmpty()
                .map { it.trim() }
                // A line of a few letters is a page number or a stray mark, not a title.
                .firstOrNull { line -> line.count(Char::isLetterOrDigit) >= 3 }
                ?.take(MAX_TITLE_LENGTH)
        }
    }

    override suspend fun delete(document: Document) {
        // The file in Storage is removed by cleanUpDocument in firebase/functions, which also
        // covers a delete made offline.
        documents(document.spaceId).document(document.id).delete().logFailure("delete document")
        activity.log(document.spaceId, ActivityAction.DELETED, document)
        withContext(ioDispatcher) {
            cacheFile(document).delete()
            keptFile(document).delete()
        }
    }

    override suspend fun fetchFile(document: Document): String? {
        if (document.kind == DocumentKind.LINK || !document.isUploaded) return null
        val file = cacheFile(document)
        return withContext(ioDispatcher) {
            keptFile(document).takeIf { it.exists() }?.let { return@withContext it.absolutePath }
            if (file.exists()) return@withContext file.absolutePath
            try {
                file.parentFile?.mkdirs()
                storage.reference.child(filePath(document.spaceId, document.id)).getFile(file).await()
                file.absolutePath
            } catch (e: StorageException) {
                file.delete()
                null
            } catch (e: IOException) {
                file.delete()
                null
            }
        }
    }

    override fun observeShares(document: Document): Flow<List<Share>> =
        // All three fields, so the rules can check a list (see shares in firebase/firestore.rules).
        db.collection(Collections.SHARES)
            .whereEqualTo(Fields.SPACE_ID, document.spaceId)
            .whereEqualTo(Fields.DOCUMENT_ID, document.id)
            .whereEqualTo(Fields.OWNER_ID, document.ownerId)
            .snapshots()
            .map { snapshot ->
                // A link that ran out stays listed until expireShares in firebase/functions gets to it.
                val now = System.currentTimeMillis()
                snapshot.documents.mapNotNull { it.toShare() }.filter { it.expiresAtMillis > now }.sortedBy { it.expiresAtMillis }
            }
            .catch { e -> if (e is FirebaseFirestoreException) emit(emptyList()) else throw e }

    override suspend fun share(document: Document, hours: Int): Share? = try {
        val data = mapOf(Fields.SPACE_ID to document.spaceId, Fields.DOCUMENT_ID to document.id, "hours" to hours)
        val result = functions.getHttpsCallable("shareDocument").call(data).await().data as Map<*, *>
        Share(
            id = result["id"] as String,
            documentId = document.id,
            createdByName = auth.requireUser().memberName,
            expiresAtMillis = (result["expiresAtMillis"] as Number).toLong(),
            url = result["url"] as String,
        )
    } catch (e: FirebaseFunctionsException) {
        Log.w("VaultAnythingShare", "shareDocument failed", e)
        null
    }

    override suspend fun unshare(shareId: String): Boolean = try {
        functions.getHttpsCallable("unshareDocument").call(mapOf("shareId" to shareId)).await()
        true
    } catch (e: FirebaseFunctionsException) {
        Log.w("VaultAnythingShare", "unshareDocument failed", e)
        false
    }

    override suspend fun isKept(document: Document): Boolean = withContext(ioDispatcher) { keptFile(document).exists() }

    override suspend fun setKept(document: Document, kept: Boolean): Boolean {
        val file = keptFile(document)
        if (!kept) {
            withContext(ioDispatcher) { file.delete() }
            return true
        }
        val fetched = fetchFile(document) ?: return false
        withContext(ioDispatcher) {
            if (fetched != file.absolutePath) {
                file.parentFile?.mkdirs()
                File(fetched).copyTo(file, overwrite = true)
            }
        }
        return true
    }

    private fun documents(spaceId: String) =
        db.collection(Collections.SPACES).document(spaceId).collection(Collections.DOCUMENTS)

    private fun newDocument(ownerId: String, title: String, kind: DocumentKind, details: DocumentDetails): Map<String, Any?> {
        val user = auth.requireUser()
        return details.toMap() + mapOf(
            Fields.OWNER_ID to ownerId,
            Fields.TITLE to title,
            Fields.KIND to kind.key,
            Fields.LOCKED to false,
            Fields.ADDED_BY_ID to user.uid,
            Fields.ADDED_BY_NAME to user.memberName,
            Fields.CREATED_AT to FieldValue.serverTimestamp(),
            Fields.UPDATED_AT to FieldValue.serverTimestamp(),
        )
    }

    /** Where the file of a document lives in Cloud Storage. firebase/storage.rules guards the same path. */
    private fun filePath(spaceId: String, documentId: String) = "spaces/$spaceId/documents/$documentId/file"

    /** Downloads are kept here, and shared from here through the app's FileProvider. */
    private fun cacheFile(document: Document) =
        File(context.cacheDir, "documents/${document.id}/${safeFileName(document.fileName)}")

    /**
     * Copies kept for travel: outside the cache, so the phone never clears them.
     * shortcut: a copy of a document deleted on another phone stays until the app's storage is cleared, add a sweep against the list if that bothers.
     */
    private fun keptFile(document: Document) =
        File(context.filesDir, "offline/${document.id}/${safeFileName(document.fileName)}")

    private class PickedFile(val name: String, val mimeType: String, val sizeBytes: Long)

    /** Blocking. What the picker said about the file, or null when it can no longer be read. */
    private fun describe(uri: Uri): PickedFile? {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                    size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                }
            }
            // Some apps leave the size out of the query; the file itself still knows it.
            if (size == null) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        return PickedFile(
            name = safeFileName(name ?: uri.lastPathSegment),
            mimeType = resolver.getType(uri)
                // A file of the app's own, such as a scan, has no provider to ask: its name says what it is.
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
                ?: "application/octet-stream",
            sizeBytes = size ?: return null,
        )
    }
}

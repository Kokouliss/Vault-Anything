package com.kokouliss.vaultanything.core.data.firebase

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.storageMetadata
import com.kokouliss.vaultanything.core.data.di.ApplicationScope
import com.kokouliss.vaultanything.core.data.di.IoDispatcher
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.MAX_FILE_BYTES
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal class FirestoreDocumentRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val thumbnails: Thumbnails,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : DocumentRepository {

    override fun observeDocuments(spaceId: String, userId: String, isAdult: Boolean): Flow<List<Document>> {
        // The rules allow a child to list only their own documents, so the query says so.
        val query: Query = if (isAdult) documents(spaceId) else documents(spaceId).whereEqualTo(Fields.OWNER_ID, userId)
        // With metadata changes, so a document says when its change has reached the cloud.
        return query.snapshots(MetadataChanges.INCLUDE)
            .map { snapshot -> snapshot.documents.mapNotNull { it.toDocument(spaceId) } }
            .distinctUntilChanged()
            // Being removed from the space, or made a child, ends the listener with "permission denied".
            .catch { e -> if (e is FirebaseFirestoreException) emit(emptyList()) else throw e }
    }

    override fun observeDocument(spaceId: String, documentId: String): Flow<Document?> =
        documents(spaceId).document(documentId).snapshots(MetadataChanges.INCLUDE)
            .map { it.toDocument(spaceId) }
            .catch { e -> if (e is FirebaseFirestoreException) emit(null) else throw e }

    override suspend fun addLink(spaceId: String, ownerId: String, title: String, link: String, details: DocumentDetails): String {
        val document = documents(spaceId).document()
        document.set(newDocument(ownerId, title, DocumentKind.LINK, details) + (Fields.LINK to link)).logFailure("add link")
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

    override suspend fun delete(document: Document) {
        // The file in Storage is removed by cleanUpDocument in firebase/functions, which also
        // covers a delete made offline.
        documents(document.spaceId).document(document.id).delete().logFailure("delete document")
        withContext(ioDispatcher) { cacheFile(document).delete() }
    }

    override suspend fun fetchFile(document: Document): String? {
        if (document.kind == DocumentKind.LINK || !document.isUploaded) return null
        val file = cacheFile(document)
        return withContext(ioDispatcher) {
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

    private fun documents(spaceId: String) =
        db.collection(Collections.SPACES).document(spaceId).collection(Collections.DOCUMENTS)

    private fun newDocument(ownerId: String, title: String, kind: DocumentKind, details: DocumentDetails): Map<String, Any?> {
        val user = auth.requireUser()
        return details.toMap() + mapOf(
            Fields.OWNER_ID to ownerId,
            Fields.TITLE to title,
            Fields.KIND to kind.key,
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
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = size ?: return null,
        )
    }
}

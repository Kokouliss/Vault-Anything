package com.kokouliss.vaultanything.core.domain.repository

import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import kotlinx.coroutines.flow.Flow

/** What came of adding a document with a file. */
sealed interface AddResult {
    data class Added(val documentId: String) : AddResult

    /** Bigger than `MAX_FILE_BYTES`. */
    data object TooLarge : AddResult

    /** The picked file could not be read, for example because the app that offered it is gone. */
    data object Unreadable : AddResult

    /** No connection, or the server refused. Nothing was kept. */
    data object Failed : AddResult
}

interface DocumentRepository {
    /**
     * Every document of the space the signed-in user may see: all of them for an adult, their own
     * for a child. [isAdult] says which query to send; the server refuses the wrong one.
     */
    fun observeDocuments(spaceId: String, userId: String, isAdult: Boolean): Flow<List<Document>>

    /** Emits null once the document is deleted or the user may no longer see it. */
    fun observeDocument(spaceId: String, documentId: String): Flow<Document?>

    /** [link] must already be normalized with `normalizeLink`. Works offline. */
    suspend fun addLink(spaceId: String, ownerId: String, title: String, link: String, details: DocumentDetails): String

    /**
     * Uploads the file at [sourceUri], a content or file URI this app may read, as a document that
     * belongs to [ownerId]. Needs a connection. The document shows up at once and says it is still
     * uploading; it is removed again if the upload fails.
     */
    suspend fun addFile(spaceId: String, ownerId: String, title: String, sourceUri: String, details: DocumentDetails): AddResult

    suspend fun update(spaceId: String, documentId: String, title: String, details: DocumentDetails)

    suspend fun setFavorite(spaceId: String, documentId: String, favorite: Boolean)

    /** Deletes the document and its file. */
    suspend fun delete(document: Document)

    /**
     * The file of [document] on this phone, downloaded once and kept in the app's cache: the path to
     * open, share or print it. Null for a link, while the file is still uploading, or when it could
     * not be fetched.
     */
    suspend fun fetchFile(document: Document): String?
}

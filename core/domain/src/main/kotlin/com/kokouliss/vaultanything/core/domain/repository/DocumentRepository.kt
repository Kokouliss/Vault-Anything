package com.kokouliss.vaultanything.core.domain.repository

import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.SHARE_HOURS
import com.kokouliss.vaultanything.core.domain.model.Share
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

    /**
     * The locked documents of another member, [ownerId], once `LockRepository.unlock` opened them
     * for a while. Empty again when that runs out, or when the server refuses.
     */
    fun observeLockedDocuments(spaceId: String, ownerId: String): Flow<List<Document>>

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

    /** Puts [document] behind its owner's PIN, or takes it out again. Only the owner may; the server says the same. */
    suspend fun setLocked(document: Document, locked: Boolean)

    /**
     * A title for a new document: the first line of words on the picture or PDF at [sourceUri],
     * read on the phone. Null when there are none, or the file is something else.
     */
    suspend fun suggestTitle(sourceUri: String): String?

    /** Deletes the document and its file. */
    suspend fun delete(document: Document)

    /**
     * The file of [document] on this phone, downloaded once and kept in the app's cache: the path to
     * open, share or print it. Null for a link, while the file is still uploading, or when it could
     * not be fetched.
     */
    suspend fun fetchFile(document: Document): String?

    /** The links to the file of [document] that still work, the one to run out first on top. */
    fun observeShares(document: Document): Flow<List<Share>>

    /** Makes a link to the file of [document] that works for [hours], one of [SHARE_HOURS]. Null when the server could not do it. */
    suspend fun share(document: Document, hours: Int): Share?

    /** Stops a link before its time. False when the server could not be reached. */
    suspend fun unshare(shareId: String): Boolean

    /** Whether the file of [document] is kept on this phone, to open without a connection. */
    suspend fun isKept(document: Document): Boolean

    /** Keeps the file of [document] on this phone, or lets it go. False when it could not be fetched. */
    suspend fun setKept(document: Document, kept: Boolean): Boolean
}

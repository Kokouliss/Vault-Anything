package com.kokouliss.vaultanything.core.domain.model

import java.text.Normalizer

/** What a document is kept under. [key] is what the database stores. */
enum class DocumentCategory(val key: String) {
    IDENTITY("identity"),
    HEALTH("health"),
    HOME("home"),
    CAR("car"),
    SCHOOL("school"),
    FINANCE("finance"),
    OTHER("other");

    companion object {
        fun fromKey(key: String?): DocumentCategory = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** What a document holds: a file of any kind, a picture, or a web link. */
enum class DocumentKind(val key: String) {
    FILE("file"),
    IMAGE("image"),
    LINK("link");

    companion object {
        fun fromKey(key: String?): DocumentKind = entries.firstOrNull { it.key == key } ?: FILE
    }
}

/** JPEG bytes. Compared by content, so a document whose picture did not change is equal to itself. */
class ImageData(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is ImageData && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

const val MAX_TITLE_LENGTH = 100
const val MAX_NOTE_LENGTH = 1000
const val MAX_TAGS = 10
const val MAX_TAG_LENGTH = 30
const val MAX_LINK_LENGTH = 2000
/** How much of the words read off a scan is kept. Enough to find a paper by what it says. */
const val MAX_TEXT_LENGTH = 10_000

/** The largest file the app uploads. firebase/storage.rules refuses bigger ones as well. */
const val MAX_FILE_BYTES = 25L * 1024 * 1024

/** The details a person fills in besides the title and the file itself. */
data class DocumentDetails(
    val category: DocumentCategory = DocumentCategory.OTHER,
    val tags: List<String> = emptyList(),
    /** The day it stops being valid, as days since 1970 in the phone's time zone. */
    val expiresDay: Long? = null,
    val note: String? = null,
    val isFavorite: Boolean = false,
)

/** One document of one member of a space. */
data class Document(
    val id: String,
    val spaceId: String,
    /** The member it belongs to, which decides who may see it. */
    val ownerId: String,
    val title: String,
    val kind: DocumentKind,
    val details: DocumentDetails,
    /** For a [DocumentKind.LINK]. */
    val link: String? = null,
    /** For a file or an image: the name it was picked with, its type, and its size once uploaded. */
    val fileName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    /** False while the file is still on its way to the server. */
    val isUploaded: Boolean = true,
    /** A small picture of the first page, for pictures and PDFs. */
    val thumbnail: ImageData? = null,
    /** The words read off a picture or a PDF on the phone, for search. Nothing for a link or a file without words. */
    val text: String? = null,
    /** Behind the owner's PIN: hidden from the list, and from other adults, until it is given. */
    val isLocked: Boolean = false,
    val addedByName: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    /** Written on this phone and not on the server yet. */
    val isPending: Boolean = false,
) {
    val isFavorite: Boolean get() = details.isFavorite

    /** Days until it expires, negative once it has, or null for a document without a date. */
    fun daysLeft(today: Long): Long? = details.expiresDay?.let { it - today }
}

/** How soon "expiring soon" is: three months, like the warning on a passport renewal. */
const val EXPIRY_WARNING_DAYS = 90L

/** The documents as a list shows them: favorites first, then the newest change on top. */
fun List<Document>.sortedForDisplay(): List<Document> =
    sortedWith(compareByDescending<Document> { it.isFavorite }.thenByDescending { it.updatedAtMillis })

/** The documents that expire within [EXPIRY_WARNING_DAYS] of [today], or already have, soonest first. */
fun List<Document>.expiringSoon(today: Long): List<Document> =
    filter { (it.daysLeft(today) ?: Long.MAX_VALUE) <= EXPIRY_WARNING_DAYS }.sortedBy { it.details.expiresDay }

private val ACCENTS = Regex("\\p{Mn}+")

/**
 * The text as search and tags compare it: no accents, no capitals and one kind of sigma, so
 * "Ασφάλεια", "ασφαλεια" and "ΑΣΦΑΛΕΙΑ" are the same word.
 */
fun String.folded(): String =
    ACCENTS.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "").lowercase().replace('ς', 'σ')

/** The tags typed as one line, separated by commas: trimmed, cut to size, no empty ones and no repeats. */
fun parseTags(input: String): List<String> = input.split(',', '\n')
    .map { it.trim().take(MAX_TAG_LENGTH) }
    .filter { it.isNotEmpty() }
    .distinctBy { it.folded() }
    .take(MAX_TAGS)

/** True when [query] is found in the title, the tags, the note, the file name or the words read off it, accents and case aside. */
fun Document.matches(query: String): Boolean {
    val needle = query.folded().trim()
    if (needle.isEmpty()) return true
    return sequenceOf(title, details.note.orEmpty(), fileName.orEmpty(), details.tags.joinToString(" "), text.orEmpty())
        .any { needle in it.folded() }
}

private val WEB_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
// The host has no ":" or "@", so "mailto:a@b.com" is not read as a web page. A port may follow it.
private val WEB_LINK = Regex("^https?://[^\\s/?#:@]+\\.[^\\s/?#:@]+(?::\\d+)?(?:[/?#]\\S*)?$")

/** The link as the app opens and stores it: "example.com/page" becomes https, anything that is not a web page is null. */
fun normalizeLink(input: String): String? {
    val text = input.trim()
    val scheme = WEB_SCHEME.find(text)?.value
    val link = if (scheme == null) "https://$text" else scheme.lowercase() + text.removePrefix(scheme)
    return link.takeIf { it.length <= MAX_LINK_LENGTH && WEB_LINK.matches(it) }
}

package com.kokouliss.vaultanything.core.domain.model

/** A link to the file of a document that anyone may open, no sign-in needed, until [expiresAtMillis]. */
data class Share(
    val id: String,
    val documentId: String,
    /** Who made it, as the family knows them. */
    val createdByName: String,
    val expiresAtMillis: Long,
    val url: String,
)

/** How long a share link works, in hours: an hour, a day or a week. shareDocument in firebase/functions takes the same. */
val SHARE_HOURS: List<Int> = listOf(1, 24, 168)

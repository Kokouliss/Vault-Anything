package com.kokouliss.vaultanything.core.domain.model

/** What a member did, as the activity log of a space tells it. [key] is what the database stores. */
enum class ActivityAction(val key: String) {
    ADDED("added"),
    OPENED("opened"),
    DELETED("deleted"),
    /** Made a link to a document's file for someone outside the family. */
    SHARED("shared"),
    /** An adult gave a child a new PIN. Written by firebase/functions. */
    PIN_RESET("pin_reset"),
    /** A child turned [ADULT_AGE] and became an adult. Written by firebase/functions. */
    GREW_UP("grew_up"),
    /** Someone's emergency contact opened their locked documents. Written by firebase/functions. */
    EMERGENCY("emergency");

    companion object {
        fun fromKey(key: String?): ActivityAction? = entries.firstOrNull { it.key == key }
    }
}

/** One line of the activity log of a space. Only adults read it. */
data class ActivityEntry(
    val id: String,
    val atMillis: Long,
    val actorId: String,
    val actorName: String,
    val action: ActivityAction,
    /** The document, for [ActivityAction.ADDED], [ActivityAction.OPENED] and [ActivityAction.DELETED]. Its title as it was then. */
    val documentId: String? = null,
    val documentTitle: String? = null,
    /** The child, for [ActivityAction.PIN_RESET]. */
    val memberId: String? = null,
    val memberName: String? = null,
)

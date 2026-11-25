package com.kokouliss.vaultanything.core.domain.model

const val MILLIS_PER_DAY = 86_400_000L

/** The waits a person can choose before their emergency contact gets in. */
val EMERGENCY_WAIT_DAYS = listOf(1, 7, 30)

/**
 * Someone the person trusts to see their locked documents in an emergency: an adult who shares a
 * space with them. The contact asks, the person is told and can say no, and after [waitDays] the
 * contact opens the documents without the PIN.
 */
data class Emergency(
    val contactId: String,
    val waitDays: Int,
    /** When the contact asked, or null while nobody has. */
    val askedAtMillis: Long? = null,
) {
    /** When the contact may open the documents, once they asked. */
    val opensAtMillis: Long? get() = askedAtMillis?.plus(waitDays * MILLIS_PER_DAY)
}

/** [ownerId] named the signed-in person as their emergency contact. */
data class Trust(val ownerId: String, val emergency: Emergency)

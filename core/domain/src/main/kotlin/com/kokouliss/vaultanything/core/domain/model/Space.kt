package com.kokouliss.vaultanything.core.domain.model

/**
 * What a member may see. Adults see every document in the space; a child sees only their own.
 * [key] is what the database stores and firebase/firestore.rules checks.
 */
enum class Role(val key: String) {
    ADULT("adult"),
    CHILD("child");

    companion object {
        fun fromKey(key: String?): Role? = entries.firstOrNull { it.key == key }
    }
}

/** A family: the people who share a space. */
data class Space(
    val id: String,
    val name: String,
    val ownerId: String,
    /** Anyone who has this code can join, as a child until an adult says otherwise. The owner can reset it. */
    val inviteCode: String,
    val members: List<Member>,
    /** Lower-case email addresses that were invited but have not joined yet. */
    val invitedEmails: List<String>,
) {
    fun member(id: String): Member? = members.firstOrNull { it.id == id }

    fun isAdult(userId: String): Boolean = member(userId)?.role == Role.ADULT

    /** Whose documents [userId] may open: everyone's for an adult, their own for a child. The same rule as the server's. */
    fun visibleMembers(userId: String): List<Member> =
        if (isAdult(userId)) members else members.filter { it.id == userId }

    /**
     * Whether [userId] may add, change or delete documents that belong to [ownerId]: their own, and a
     * child's when they are an adult. An adult's documents are theirs alone to change.
     */
    fun canManageDocumentsOf(userId: String, ownerId: String): Boolean =
        userId == ownerId || (isAdult(userId) && member(ownerId)?.role == Role.CHILD)
}

/** How many years old a child is when comeOfAge in firebase/functions makes them an adult. */
const val ADULT_AGE = 18

data class Member(
    val id: String,
    val name: String,
    val role: Role = Role.CHILD,
    /** Days since 1 January 1970, set by an adult. A child becomes an adult on their [ADULT_AGE]th birthday. */
    val birthDay: Long? = null,
)

/** Outcome of trying to join a space with an invite code. */
enum class JoinResult { Joined, AlreadyMember, InvalidCode, Unavailable }

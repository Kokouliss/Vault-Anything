package com.kokouliss.vaultanything.core.data.firebase

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.kokouliss.vaultanything.core.domain.model.ActivityAction
import com.kokouliss.vaultanything.core.domain.model.ActivityEntry
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.Emergency
import com.kokouliss.vaultanything.core.domain.model.ImageData
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Share
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.normalizeLink
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy

// Document layout. firebase/firestore.rules and firebase/storage.rules validate the same names; change them together.
//
// spaces/{spaceId}                         name, ownerId, memberIds[], members{uid: {name, role, birthDay?}}, inviteCode, invitedEmails[], createdAt
//                                          role is "adult" or "child". The owner is always an adult. birthDay (days since 1970) is set
//                                          by an adult; comeOfAge in firebase/functions makes the child an adult on their 18th birthday.
// invites/{inviteCode}                     spaceId
// spaces/{spaceId}/documents/{documentId}  ownerId: whose document it is; title, kind (file|image|link), category, tags[], favorite,
//                                          expiresDay?, note?, link?, fileName?, mimeType?, sizeBytes?, uploaded?, thumbnail? (JPEG bytes),
//                                          text?, locked, addedById, addedByName, createdAt, updatedAt
//                                          locked: behind the owner's PIN. Other adults see it only with an unlock (below).
// spaces/{spaceId}/activity/{entryId}      at, actorId, actorName, action (added|opened|deleted|shared|pin_reset|grew_up|emergency),
//                                          documentId?, documentTitle?, memberId?, memberName?. Read by adults; written by the member
//                                          it is about, the last four by functions.
// spaces/{spaceId}/unlocks/{memberId}      grants {ownerId: until}. Written by verifyPin in firebase/functions, read by the rules only.
// users/{uid}                              hasPin, emergency? {contactId, waitDays, askedAt?}. hasPin is written by setPin in
//                                          firebase/functions; the PIN's hash sits under users/{uid}/private/lock, which no phone reads.
//                                          emergency is the person's own choice of contact and wait; askedAt is written by askEmergency
//                                          in firebase/functions and cleared by the person. The contact lists the people who named them.
// users/{uid}/tokens/{token}               updatedAt. One per device that gets this person's notifications.
// shares/{shareId}                         spaceId, documentId, ownerId, createdById, createdByName, title, fileName, token, url,
//                                          expiresAt, createdAt. A link to one file for anyone, until expiresAt. Written by
//                                          shareDocument in firebase/functions; the phone only lists a document's links.
//
// The file of a document is in Cloud Storage at spaces/{spaceId}/documents/{documentId}/{fileName}.

internal object Collections {
    const val SPACES = "spaces"
    const val INVITES = "invites"
    const val DOCUMENTS = "documents"
    const val USERS = "users"
    const val TOKENS = "tokens"
    const val ACTIVITY = "activity"
    const val SHARES = "shares"
}

internal object Fields {
    const val NAME = "name"
    const val ROLE = "role"
    const val OWNER_ID = "ownerId"
    const val MEMBER_IDS = "memberIds"
    const val MEMBERS = "members"
    const val INVITE_CODE = "inviteCode"
    const val INVITED_EMAILS = "invitedEmails"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val SPACE_ID = "spaceId"
    const val TITLE = "title"
    const val KIND = "kind"
    const val CATEGORY = "category"
    const val TAGS = "tags"
    const val FAVORITE = "favorite"
    const val EXPIRES_DAY = "expiresDay"
    const val NOTE = "note"
    const val LINK = "link"
    const val FILE_NAME = "fileName"
    const val MIME_TYPE = "mimeType"
    const val SIZE_BYTES = "sizeBytes"
    const val UPLOADED = "uploaded"
    const val THUMBNAIL = "thumbnail"
    const val TEXT = "text"
    const val LOCKED = "locked"
    const val ADDED_BY_ID = "addedById"
    const val ADDED_BY_NAME = "addedByName"
    const val HAS_PIN = "hasPin"
    const val BIRTH_DAY = "birthDay"
    const val EMERGENCY = "emergency"
    const val CONTACT_ID = "contactId"
    const val WAIT_DAYS = "waitDays"
    const val ASKED_AT = "askedAt"
    const val AT = "at"
    const val ACTOR_ID = "actorId"
    const val ACTOR_NAME = "actorName"
    const val ACTION = "action"
    const val DOCUMENT_ID = "documentId"
    const val DOCUMENT_TITLE = "documentTitle"
    const val MEMBER_ID = "memberId"
    const val MEMBER_NAME = "memberName"
    const val CREATED_BY_NAME = "createdByName"
    const val EXPIRES_AT = "expiresAt"
    const val URL = "url"
}

internal fun DocumentSnapshot.toSpace(): Space? {
    if (!exists()) return null
    val ownerId = getString(Fields.OWNER_ID) ?: return null
    val members = (get(Fields.MEMBERS) as? Map<*, *>).orEmpty()
        .mapNotNull { (id, entry) ->
            val map = entry as? Map<*, *>
            val name = map?.get(Fields.NAME) as? String
            if (id is String && name != null) {
                Member(id, name, Role.fromKey(map[Fields.ROLE] as? String) ?: Role.CHILD, (map[Fields.BIRTH_DAY] as? Number)?.toLong())
            } else {
                null
            }
        }
        .sortedWith(compareByDescending<Member> { it.id == ownerId }.thenBy { it.role }.thenBy { it.name.lowercase() })
    return Space(
        id = id,
        name = getString(Fields.NAME).orEmpty(),
        ownerId = ownerId,
        inviteCode = getString(Fields.INVITE_CODE).orEmpty(),
        members = members,
        invitedEmails = (get(Fields.INVITED_EMAILS) as? List<*>).orEmpty().filterIsInstance<String>(),
    )
}

internal fun DocumentSnapshot.toDocument(spaceId: String): Document? {
    if (!exists()) return null
    val ownerId = getString(Fields.OWNER_ID) ?: return null
    return Document(
        id = id,
        spaceId = spaceId,
        ownerId = ownerId,
        title = getString(Fields.TITLE).orEmpty(),
        kind = DocumentKind.fromKey(getString(Fields.KIND)),
        details = DocumentDetails(
            category = DocumentCategory.fromKey(getString(Fields.CATEGORY)),
            tags = (get(Fields.TAGS) as? List<*>).orEmpty().filterIsInstance<String>(),
            expiresDay = getLong(Fields.EXPIRES_DAY),
            note = getString(Fields.NOTE),
            isFavorite = getBoolean(Fields.FAVORITE) == true,
        ),
        // Checked again here: the link was written by another member's device and is opened with a tap.
        link = getString(Fields.LINK)?.let(::normalizeLink),
        fileName = getString(Fields.FILE_NAME),
        mimeType = getString(Fields.MIME_TYPE),
        sizeBytes = getLong(Fields.SIZE_BYTES),
        // A link has no file to wait for.
        isUploaded = getBoolean(Fields.UPLOADED) != false,
        thumbnail = getBlob(Fields.THUMBNAIL)?.let { ImageData(it.toBytes()) },
        text = getString(Fields.TEXT),
        isLocked = getBoolean(Fields.LOCKED) == true,
        addedByName = getString(Fields.ADDED_BY_NAME).orEmpty(),
        // A just-added document has no server time yet; the estimate keeps it on top of the list.
        createdAtMillis = getTimestamp(Fields.CREATED_AT, ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
        updatedAtMillis = getTimestamp(Fields.UPDATED_AT, ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
        isPending = metadata.hasPendingWrites(),
    )
}

internal fun DocumentSnapshot.toActivityEntry(): ActivityEntry? {
    val action = ActivityAction.fromKey(getString(Fields.ACTION)) ?: return null
    return ActivityEntry(
        id = id,
        atMillis = getTimestamp(Fields.AT, ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
        actorId = getString(Fields.ACTOR_ID).orEmpty(),
        actorName = getString(Fields.ACTOR_NAME).orEmpty(),
        action = action,
        documentId = getString(Fields.DOCUMENT_ID),
        documentTitle = getString(Fields.DOCUMENT_TITLE),
        memberId = getString(Fields.MEMBER_ID),
        memberName = getString(Fields.MEMBER_NAME),
    )
}

internal fun DocumentSnapshot.toShare(): Share? {
    val url = getString(Fields.URL) ?: return null
    return Share(
        id = id,
        documentId = getString(Fields.DOCUMENT_ID).orEmpty(),
        createdByName = getString(Fields.CREATED_BY_NAME).orEmpty(),
        expiresAtMillis = getTimestamp(Fields.EXPIRES_AT)?.toDate()?.time ?: 0L,
        url = url,
    )
}

/** The emergency contact a users/{uid} document names, or null without one. */
internal fun DocumentSnapshot.toEmergency(): Emergency? {
    val map = get(Fields.EMERGENCY) as? Map<*, *> ?: return null
    val contactId = map[Fields.CONTACT_ID] as? String ?: return null
    return Emergency(
        contactId = contactId,
        waitDays = (map[Fields.WAIT_DAYS] as? Number)?.toInt() ?: return null,
        askedAtMillis = (map[Fields.ASKED_AT] as? Timestamp)?.toDate()?.time,
    )
}

/** The fields of [details] as the document stores them. A detail that is not set is written as absent. */
internal fun DocumentDetails.toMap(): Map<String, Any?> = mapOf(
    Fields.CATEGORY to category.key,
    Fields.TAGS to tags,
    Fields.FAVORITE to isFavorite,
    Fields.EXPIRES_DAY to expiresDay,
    Fields.NOTE to note?.takeIf { it.isNotBlank() },
)

/**
 * The signed-in user, again whenever the name or the confirmed state of the email changes. Those
 * change without a new sign-in, so this follows the token (renewed on purpose after each change)
 * rather than the sign-in state, and skips the hourly renewals that change nothing.
 */
internal fun FirebaseAuth.userFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.IdTokenListener { trySend(it.currentUser) }
    addIdTokenListener(listener)
    awaitClose { removeIdTokenListener(listener) }
}.distinctUntilChangedBy { user -> user?.let { Triple(it.uid, it.displayName, it.isEmailVerified) } }

internal fun FirebaseAuth.requireUser(): FirebaseUser = checkNotNull(currentUser) { "No user is signed in" }

/** The name other members see next to this person's documents. */
internal val FirebaseUser.memberName: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "?"

/** The address email invites are matched against. The security rules require it to be verified. */
internal val FirebaseUser.verifiedEmail: String?
    get() = email?.lowercase()?.takeIf { isEmailVerified }

/**
 * Offline-first writes are not awaited: Firestore applies them on the device at once and syncs
 * later. A rejection (for example by the security rules) arrives much later, so it is only logged.
 */
internal fun Task<*>.logFailure(action: String) {
    addOnFailureListener { Log.w("VaultAnythingSync", "Could not $action", it) }
}

package com.kokouliss.vaultanything.feature.family

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The signed-in start screen: the user's family spaces and pending invitations. */
@Serializable
data object SpacesKey : NavKey

/** One family space: the documents this member may see, the members and the invites. */
@Serializable
data class SpaceDetailKey(val spaceId: String) : NavKey

/** Who added, opened and deleted what in a space. For adults. */
@Serializable
data class ActivityKey(val spaceId: String) : NavKey

fun EntryProviderScope<NavKey>.familyEntries(
    onOpenSpace: (spaceId: String) -> Unit,
    onOpenDocument: (spaceId: String, documentId: String) -> Unit,
    /** Adds a document for [ownerId], the member whose documents are being looked at. */
    onAddDocument: (spaceId: String, ownerId: String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenActivity: (spaceId: String) -> Unit,
    onBack: () -> Unit,
    /** The space was deleted or access to it ended: the screen closes and [message] goes on the one below. */
    onGone: (message: String) -> Unit,
    /** A message from a screen that closed itself, shown once by the screen now on top. */
    notice: String?,
    onNoticeShown: () -> Unit,
    /** The code of an invite link that opened the app. The spaces screen joins with it. */
    inviteCode: String? = null,
    onInviteHandled: () -> Unit = {},
) {
    entry<SpacesKey> {
        SpacesRoute(
            onOpenSpace = onOpenSpace,
            onOpenProfile = onOpenProfile,
            notice = notice,
            onNoticeShown = onNoticeShown,
            inviteCode = inviteCode,
            onInviteHandled = onInviteHandled,
        )
    }
    entry<SpaceDetailKey> { key ->
        SpaceDetailRoute(
            viewModel = hiltViewModel<SpaceDetailViewModel, SpaceDetailViewModel.Factory> { it.create(key) },
            onOpenDocument = { documentId -> onOpenDocument(key.spaceId, documentId) },
            onAddDocument = { ownerId -> onAddDocument(key.spaceId, ownerId) },
            onBack = onBack,
            onGone = onGone,
            notice = notice,
            onNoticeShown = onNoticeShown,
            onOpenActivity = { onOpenActivity(key.spaceId) },
        )
    }
    entry<ActivityKey> { key ->
        ActivityRoute(
            viewModel = hiltViewModel<ActivityViewModel, ActivityViewModel.Factory> { it.create(key) },
            onBack = onBack,
        )
    }
}

package com.kokouliss.vaultanything

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.EmptyState
import com.kokouliss.vaultanything.core.ui.component.LoadingBox
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import com.kokouliss.vaultanything.core.ui.theme.pageChange
import com.kokouliss.vaultanything.core.ui.theme.rememberReducedMotion
import com.kokouliss.vaultanything.feature.auth.FirstNamePrompt
import com.kokouliss.vaultanything.feature.auth.ProfileKey
import com.kokouliss.vaultanything.feature.auth.SignInRoute
import com.kokouliss.vaultanything.feature.auth.profileEntries
import com.kokouliss.vaultanything.feature.documents.DocumentDetailKey
import com.kokouliss.vaultanything.feature.documents.EditDocumentKey
import com.kokouliss.vaultanything.feature.documents.ShareKey
import com.kokouliss.vaultanything.feature.documents.documentsEntries
import com.kokouliss.vaultanything.feature.family.SpaceDetailKey
import com.kokouliss.vaultanything.feature.family.SpacesKey
import com.kokouliss.vaultanything.feature.family.familyEntries
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal enum class AuthState { Loading, SignedOut, SignedIn }

@HiltViewModel
internal class AppViewModel @Inject constructor(private val authRepository: AuthRepository) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.currentUser
        .map { user -> if (user == null) AuthState.SignedOut else AuthState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthState.Loading)

    init {
        // On every start and after every sign-in, because the address notifications go to can change.
        viewModelScope.launch {
            authRepository.currentUser.filterNotNull().collect { authRepository.registerDevice() }
        }
    }

    /** Each time the app comes back, for example from the mail app after tapping the confirmation link. */
    fun onAppStart() {
        viewModelScope.launch { authRepository.refreshEmailVerification() }
    }
}

@Composable
internal fun VaultAnythingApp(launch: Launch, onLaunchHandled: () -> Unit, viewModel: AppViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        viewModel.onAppStart()
        onStopOrDispose {}
    }
    when (authState) {
        AuthState.Loading -> Surface { LoadingBox() }
        AuthState.SignedOut -> SignInRoute()
        // Until then [launch] waits, so an invite link also works for someone who signs in first.
        AuthState.SignedIn -> {
            SignedInNavigation(launch, onLaunchHandled)
            FirstNamePrompt()
        }
    }
}

@Composable
private fun SignedInNavigation(launch: Launch, onLaunchHandled: () -> Unit) {
    val backStack = rememberNavBackStack(SpacesKey)
    val goBack: () -> Unit = { backStack.removeLastOrNull() }
    // A screen that closes itself because its document or space is gone says so on the screen below it.
    var notice by remember { mutableStateOf<String?>(null) }
    val onGone = { message: String ->
        goBack()
        notice = message
    }

    LaunchedEffect(launch) {
        launch.shared?.let {
            backStack.add(ShareKey(uri = it.uri, text = it.text))
            onLaunchHandled()
        }
    }

    // Deeper comes in from the right, back from the left, also under a back swipe.
    val slide = !rememberReducedMotion()
    NavDisplay(
        backStack = backStack,
        onBack = goBack,
        transitionSpec = { pageChange(forward = true, slide) },
        popTransitionSpec = { pageChange(forward = false, slide) },
        predictivePopTransitionSpec = { pageChange(forward = false, slide) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            familyEntries(
                onOpenSpace = { spaceId -> backStack.add(SpaceDetailKey(spaceId)) },
                onOpenDocument = { spaceId, documentId -> backStack.add(DocumentDetailKey(spaceId, documentId)) },
                onAddDocument = { spaceId, ownerId -> backStack.add(EditDocumentKey(spaceId, ownerId = ownerId)) },
                onOpenProfile = { backStack.add(ProfileKey) },
                onBack = goBack,
                onGone = onGone,
                notice = notice,
                onNoticeShown = { notice = null },
                inviteCode = launch.inviteCode,
                onInviteHandled = onLaunchHandled,
            )
            documentsEntries(
                onBack = goBack,
                onGone = onGone,
                onEdit = { spaceId, documentId -> backStack.add(EditDocumentKey(spaceId, documentId = documentId)) },
                onShareInto = { spaceId, uri, text ->
                    // The editor takes the place of the "which family?" screen.
                    backStack.removeLastOrNull()
                    backStack.add(EditDocumentKey(spaceId, sharedUri = uri, sharedText = text))
                },
                onSharedSaved = { spaceId ->
                    // The family's vault takes the place of the editor, with the new document on top.
                    backStack.removeLastOrNull()
                    backStack.add(SpaceDetailKey(spaceId))
                },
            )
            profileEntries(onBack = goBack)
        },
    )
}

/** Shown by builds made without `app/google-services.json`, instead of crashing on the first Firebase call. */
@Composable
internal fun SetupNeededScreen() {
    Surface {
        EmptyState(
            icon = AppIcons.Refresh,
            title = stringResource(R.string.setup_needed_title),
            body = stringResource(R.string.setup_needed_body),
        )
    }
}

@PreviewLightDark
@Composable
private fun SetupNeededScreenPreview() {
    VaultAnythingTheme { SetupNeededScreen() }
}

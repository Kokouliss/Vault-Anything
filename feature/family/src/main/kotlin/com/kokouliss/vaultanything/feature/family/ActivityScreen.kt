package com.kokouliss.vaultanything.feature.family

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.ActivityAction
import com.kokouliss.vaultanything.core.domain.model.ActivityEntry
import com.kokouliss.vaultanything.core.domain.repository.ActivityRepository
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.BackButton
import com.kokouliss.vaultanything.core.ui.component.EmptyState
import com.kokouliss.vaultanything.core.ui.component.InitialAvatar
import com.kokouliss.vaultanything.core.ui.component.LoadingCrossfade
import com.kokouliss.vaultanything.core.ui.component.pageWidth
import com.kokouliss.vaultanything.core.ui.component.rememberPaperScroll
import com.kokouliss.vaultanything.core.ui.component.ruledPaper
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = ActivityViewModel.Factory::class)
internal class ActivityViewModel @AssistedInject constructor(
    @Assisted key: ActivityKey,
    activityRepository: ActivityRepository,
) : ViewModel() {
    /** Newest first. Null while loading. */
    val entries: StateFlow<List<ActivityEntry>?> = activityRepository.observeActivity(key.spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @AssistedFactory
    interface Factory {
        fun create(key: ActivityKey): ActivityViewModel
    }
}

@Composable
internal fun ActivityRoute(viewModel: ActivityViewModel, onBack: () -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    ActivityScreen(entries, onBack)
}

/** Who added, opened and deleted what, for the adults of the space. */
@Composable
internal fun ActivityScreen(entries: List<ActivityEntry>?, onBack: () -> Unit) {
    val title = stringResource(R.string.feature_family_activity)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .ruledPaper(MaterialTheme.colorScheme.primary, rememberPaperScroll())
                .padding(padding)
                .pageWidth(),
        ) {
            LoadingCrossfade(entries == null) {
                if (entries == null) return@LoadingCrossfade
                if (entries.isEmpty()) {
                    EmptyState(
                        icon = AppIcons.History,
                        title = stringResource(R.string.feature_family_activity_empty_title),
                        body = stringResource(R.string.feature_family_activity_empty_body),
                    )
                } else {
                    val context = LocalContext.current
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { InitialAvatar(entry.actorName) },
                                headlineContent = { Text(entry.sentence()) },
                                supportingContent = {
                                    Text(DateUtils.getRelativeTimeSpanString(context, entry.atMillis).toString())
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityEntry.sentence(): String = when (action) {
    ActivityAction.ADDED -> stringResource(R.string.feature_family_activity_added, actorName, documentTitle.orEmpty())
    ActivityAction.OPENED -> stringResource(R.string.feature_family_activity_opened, actorName, documentTitle.orEmpty())
    ActivityAction.DELETED -> stringResource(R.string.feature_family_activity_deleted, actorName, documentTitle.orEmpty())
    ActivityAction.SHARED -> stringResource(R.string.feature_family_activity_shared, actorName, documentTitle.orEmpty())
    ActivityAction.PIN_RESET -> stringResource(R.string.feature_family_activity_pin_reset, actorName, memberName.orEmpty())
}

@PreviewLightDark
@Composable
private fun ActivityScreenPreview() {
    VaultAnythingTheme {
        ActivityScreen(
            entries = listOf(
                ActivityEntry("a1", 0L, "u1", "Panos", ActivityAction.ADDED, "d1", "Passport"),
                ActivityEntry("a2", 0L, "u2", "Maria", ActivityAction.OPENED, "d1", "Passport"),
                ActivityEntry("a3", 0L, "u1", "Panos", ActivityAction.PIN_RESET, memberId = "u3", memberName = "Nikos"),
            ),
            onBack = {},
        )
    }
}

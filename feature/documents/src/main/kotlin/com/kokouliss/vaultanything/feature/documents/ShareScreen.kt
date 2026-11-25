package com.kokouliss.vaultanything.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.BackButton
import com.kokouliss.vaultanything.core.ui.component.EmptyState
import com.kokouliss.vaultanything.core.ui.component.LoadingCrossfade
import com.kokouliss.vaultanything.core.ui.component.pageWidth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
internal class ShareViewModel @Inject constructor(spaceRepository: SpaceRepository) : ViewModel() {
    /** Null until the spaces have arrived. */
    val spaces: StateFlow<List<Space>?> = spaceRepository.observeSpaces()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** "Which family space?" for something shared from another app. One space needs no asking. */
@Composable
internal fun ShareRoute(onPickSpace: (spaceId: String) -> Unit, onBack: () -> Unit, viewModel: ShareViewModel = hiltViewModel()) {
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    LaunchedEffect(spaces) {
        if (spaces?.size == 1) onPickSpace(spaces!!.single().id)
    }
    val title = stringResource(R.string.feature_documents_share_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pageWidth()) {
            LoadingCrossfade(spaces == null || spaces?.size == 1) {
                val list = spaces.orEmpty()
                if (list.isEmpty()) {
                    EmptyState(
                        icon = AppIcons.Group,
                        title = stringResource(R.string.feature_documents_share_none_title),
                        body = stringResource(R.string.feature_documents_share_none_body),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { space ->
                            ElevatedCard(onClick = { onPickSpace(space.id) }, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(space.name, style = MaterialTheme.typography.titleMedium) },
                                    supportingContent = {
                                        Text(pluralStringResource(R.plurals.feature_documents_member_count, space.members.size, space.members.size))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.kokouliss.vaultanything.feature.family

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.InviteCode
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.normalizeEmail
import com.kokouliss.vaultanything.core.domain.model.Role as MemberRole
import com.kokouliss.vaultanything.core.ui.R as CoreR
import com.kokouliss.vaultanything.core.ui.component.AppIcon
import com.kokouliss.vaultanything.core.ui.component.AppIcons
import com.kokouliss.vaultanything.core.ui.component.BackButton
import com.kokouliss.vaultanything.core.ui.component.ConfirmDialog
import com.kokouliss.vaultanything.core.ui.component.DocumentThumbnail
import com.kokouliss.vaultanything.core.ui.component.EmptyState
import com.kokouliss.vaultanything.core.ui.component.InitialAvatar
import com.kokouliss.vaultanything.core.ui.component.LoadingCrossfade
import com.kokouliss.vaultanything.core.ui.component.PopIn
import com.kokouliss.vaultanything.core.ui.component.TextInputDialog
import com.kokouliss.vaultanything.core.ui.component.UndoSnackbar
import com.kokouliss.vaultanything.core.ui.component.destructiveMenuItemColors
import com.kokouliss.vaultanything.core.ui.component.expiryLabel
import com.kokouliss.vaultanything.core.ui.component.label
import com.kokouliss.vaultanything.core.ui.component.pageWidth
import com.kokouliss.vaultanything.core.ui.component.pressScale
import com.kokouliss.vaultanything.core.ui.component.rememberPaperScroll
import com.kokouliss.vaultanything.core.ui.component.ruledPaper
import com.kokouliss.vaultanything.core.ui.theme.VaultAnythingTheme
import kotlinx.coroutines.launch

private enum class SpaceDialog { None, Rename, Leave, Delete, ResetCode }

@Composable
internal fun SpaceDetailRoute(
    viewModel: SpaceDetailViewModel,
    onOpenDocument: (documentId: String) -> Unit,
    onAddDocument: (ownerId: String) -> Unit,
    onBack: () -> Unit,
    onGone: (message: String) -> Unit,
    notice: String?,
    onNoticeShown: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (uiState is SpaceDetailUiState.Gone) {
        LaunchedEffect(Unit) { onGone(resources.getString(R.string.feature_family_space_gone)) }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            scope.launch { snackbarHostState.showSnackbar(notice) }
            onNoticeShown()
        }
    }
    UndoSnackbar(viewModel.cancelledInvites, snackbarHostState) {
        resources.getString(R.string.feature_family_invite_cancelled, it)
    }

    SpaceDetailScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenDocument = onOpenDocument,
        onAddDocument = onAddDocument,
        onShowMember = viewModel::showMember,
        onShowCategory = viewModel::showCategory,
        onShowFavorites = viewModel::showFavorites,
        onRenameSpace = viewModel::renameSpace,
        onShareCode = { space ->
            val text = resources.getString(
                R.string.feature_family_share_text,
                space.name,
                space.inviteCode,
                InviteCode.link(space.inviteCode),
            )
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
        },
        onResetCode = viewModel::resetInviteCode,
        onInviteEmail = viewModel::inviteEmail,
        onCancelEmailInvite = viewModel::cancelEmailInvite,
        onSetRole = viewModel::setRole,
        onRemoveMember = viewModel::removeMember,
        onLeaveSpace = viewModel::leaveSpace,
        onDeleteSpace = viewModel::deleteSpace,
    )
}

@Composable
internal fun SpaceDetailScreen(
    uiState: SpaceDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenDocument: (documentId: String) -> Unit,
    onAddDocument: (ownerId: String) -> Unit,
    onShowMember: (memberId: String?) -> Unit,
    onShowCategory: (DocumentCategory?) -> Unit,
    onShowFavorites: () -> Unit,
    onRenameSpace: (String) -> Unit,
    onShareCode: (Space) -> Unit,
    onResetCode: () -> Unit,
    onInviteEmail: (String) -> Unit,
    onCancelEmailInvite: (String) -> Unit,
    onSetRole: (memberId: String, MemberRole) -> Unit,
    onRemoveMember: (memberId: String) -> Unit,
    onLeaveSpace: () -> Unit,
    onDeleteSpace: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf(SpaceDialog.None) }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var memberToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = uiState as? SpaceDetailUiState.Ready
    val closeDialog = { dialog = SpaceDialog.None }

    val title = ready?.space?.name.orEmpty()
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        // The open sheet covers this host, so it shows its own copy of the snackbar instead.
        snackbarHost = { if (!showMembers) SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { showMembers = true }) {
                            AppIcon(AppIcons.Group, stringResource(R.string.feature_family_members))
                        }
                        SpaceMenu(isOwner = ready.isOwner, onSelect = { dialog = it })
                    }
                },
            )
        },
        floatingActionButton = {
            // The ink blue of every other main action, not Material's pad yellow.
            PopIn(ready != null && ready.allDocuments.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { ready?.let { onAddDocument(it.addFor) } },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { AppIcon(AppIcons.Add, null) },
                    text = { Text(stringResource(R.string.feature_family_add_document)) },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .ruledPaper(MaterialTheme.colorScheme.primary, rememberPaperScroll())
                .padding(padding)
                .pageWidth(),
        ) {
            LoadingCrossfade(ready == null) {
                if (ready == null) return@LoadingCrossfade
                if (ready.allDocuments.isEmpty()) {
                    EmptyState(
                        icon = AppIcons.Folder,
                        title = stringResource(R.string.feature_family_documents_empty_title),
                        body = stringResource(R.string.feature_family_documents_empty_body),
                    ) {
                        Button(onClick = { onAddDocument(ready.addFor) }) {
                            Text(stringResource(R.string.feature_family_add_document))
                        }
                    }
                } else {
                    Documents(ready, onOpenDocument, onShowMember, onShowCategory, onShowFavorites)
                }
            }
        }
    }

    if (ready != null && showMembers) {
        MembersSheet(
            state = ready,
            snackbarHostState = snackbarHostState,
            onDismiss = { showMembers = false },
            onShareCode = { onShareCode(ready.space) },
            onResetCode = { dialog = SpaceDialog.ResetCode },
            onInviteEmail = onInviteEmail,
            onCancelEmailInvite = onCancelEmailInvite,
            onSetRole = onSetRole,
            onRemoveMember = { memberToRemove = it },
        )
    }

    when (dialog) {
        SpaceDialog.None -> Unit
        SpaceDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.feature_family_rename_space),
            label = stringResource(R.string.feature_family_space_name),
            confirmLabel = stringResource(CoreR.string.core_ui_save),
            initialValue = ready?.space?.name.orEmpty(),
            onConfirm = {
                onRenameSpace(it)
                closeDialog()
            },
            onDismiss = closeDialog,
        )
        SpaceDialog.Leave -> ConfirmDialog(
            title = stringResource(R.string.feature_family_leave_space),
            text = stringResource(R.string.feature_family_leave_confirm_text),
            confirmLabel = stringResource(R.string.feature_family_leave),
            onConfirm = {
                closeDialog()
                onLeaveSpace()
            },
            onDismiss = closeDialog,
        )
        SpaceDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.feature_family_delete_space),
            text = stringResource(R.string.feature_family_delete_confirm_text),
            confirmLabel = stringResource(CoreR.string.core_ui_delete),
            onConfirm = {
                closeDialog()
                onDeleteSpace()
            },
            onDismiss = closeDialog,
        )
        SpaceDialog.ResetCode -> ConfirmDialog(
            title = stringResource(R.string.feature_family_reset_code_confirm_title),
            text = stringResource(R.string.feature_family_reset_code_confirm_text),
            confirmLabel = stringResource(R.string.feature_family_reset_code),
            onConfirm = {
                closeDialog()
                onResetCode()
            },
            onDismiss = closeDialog,
        )
    }

    val removing = ready?.space?.members?.firstOrNull { it.id == memberToRemove }
    if (removing != null) {
        ConfirmDialog(
            title = stringResource(R.string.feature_family_remove_member, removing.name),
            text = stringResource(R.string.feature_family_remove_member_confirm_text, removing.name),
            confirmLabel = stringResource(R.string.feature_family_remove),
            onConfirm = {
                memberToRemove = null
                onRemoveMember(removing.id)
            },
            onDismiss = { memberToRemove = null },
        )
    }
}

@Composable
private fun Documents(
    state: SpaceDetailUiState.Ready,
    onOpenDocument: (String) -> Unit,
    onShowMember: (String?) -> Unit,
    onShowCategory: (DocumentCategory?) -> Unit,
    onShowFavorites: () -> Unit,
) {
    val members = state.visibleMembers
    val names = members.associate { it.id to it.name }
    LazyColumn(
        // Bottom room so the last row is not hidden behind the floating button.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.expiring.isNotEmpty()) {
            item("expiring") { ExpiringCard(state.expiring, state.today, names, onOpenDocument) }
        }
        // Whose documents: only for an adult, who sees more than their own.
        if (members.size > 1) {
            item("members") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = state.filter.memberId == null,
                            onClick = { onShowMember(null) },
                            label = { Text(stringResource(R.string.feature_family_everyone)) },
                        )
                    }
                    items(members, key = { it.id }) { member ->
                        FilterChip(
                            selected = state.filter.memberId == member.id,
                            onClick = { onShowMember(member.id) },
                            label = { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
        item("categories") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = state.filter.category == null && !state.filter.favoritesOnly,
                        onClick = { onShowCategory(null) },
                        label = { Text(stringResource(R.string.feature_family_all)) },
                    )
                }
                item {
                    FilterChip(
                        selected = state.filter.favoritesOnly,
                        onClick = onShowFavorites,
                        leadingIcon = { AppIcon(AppIcons.Star, null, Modifier.size(18.dp)) },
                        label = { Text(stringResource(R.string.feature_family_favorites)) },
                    )
                }
                items(DocumentCategory.entries, key = { it.key }) { category ->
                    FilterChip(
                        selected = state.filter.category == category,
                        onClick = { onShowCategory(category) },
                        label = { Text(stringResource(category.label)) },
                    )
                }
            }
        }
        if (state.documents.isEmpty()) {
            item("nothing") {
                Text(
                    stringResource(R.string.feature_family_nothing_here),
                    Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(state.documents, key = { it.id }) { document ->
            DocumentCard(
                document = document,
                ownerName = names[document.ownerId].takeIf { members.size > 1 },
                today = state.today,
                onClick = { onOpenDocument(document.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun DocumentCard(document: Document, ownerName: String?, today: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val touch = remember { MutableInteractionSource() }
    val favoriteLabel = stringResource(R.string.feature_family_favorite)
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.pressScale(touch).fillMaxWidth().semantics { role = Role.Button },
        interactionSource = touch,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { DocumentThumbnail(document) },
            headlineContent = {
                Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                val parts = listOfNotNull(
                    ownerName,
                    stringResource(document.details.category.label),
                    document.daysLeft(today)?.let { expiryLabel(it) },
                    if (document.isUploaded) null else stringResource(R.string.feature_family_uploading),
                )
                Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = if (document.isFavorite) {
                { AppIcon(AppIcons.Star, favoriteLabel, tint = MaterialTheme.colorScheme.tertiary) }
            } else {
                null
            },
        )
    }
}

/** The documents that run out soon, as a note on top of the list. */
@Composable
private fun ExpiringCard(expiring: List<Document>, today: Long, names: Map<String, String>, onOpenDocument: (String) -> Unit) {
    Card(
        // Yellow, like a sticky note left on the folder.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.feature_family_expiring_title),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            expiring.forEach { document ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val owner = names[document.ownerId]
                        val days = document.daysLeft(today)?.let { expiryLabel(it) }
                        Text(listOfNotNull(owner, days).joinToString(" · "))
                    },
                    modifier = Modifier.clickable(role = Role.Button) { onOpenDocument(document.id) },
                )
            }
        }
    }
}

@Composable
private fun SpaceMenu(isOwner: Boolean, onSelect: (SpaceDialog) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val select = { target: SpaceDialog ->
        expanded = false
        onSelect(target)
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            AppIcon(AppIcons.MoreVert, stringResource(CoreR.string.core_ui_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isOwner) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_family_rename_space)) },
                    leadingIcon = { AppIcon(AppIcons.Edit, null) },
                    onClick = { select(SpaceDialog.Rename) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_family_delete_space)) },
                    leadingIcon = { AppIcon(AppIcons.Delete, null) },
                    colors = destructiveMenuItemColors(),
                    onClick = { select(SpaceDialog.Delete) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_family_leave_space)) },
                    leadingIcon = { AppIcon(AppIcons.Logout, null) },
                    onClick = { select(SpaceDialog.Leave) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersSheet(
    state: SpaceDetailUiState.Ready,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onShareCode: () -> Unit,
    onResetCode: () -> Unit,
    onInviteEmail: (String) -> Unit,
    onCancelEmailInvite: (String) -> Unit,
    onSetRole: (memberId: String, MemberRole) -> Unit,
    onRemoveMember: (memberId: String) -> Unit,
) {
    val space = state.space
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Box {
            Column(
                Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.feature_family_invite_code_title))
                Hint(stringResource(R.string.feature_family_invite_code_hint))
                SelectionContainer {
                    Text(
                        space.inviteCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    )
                }
                Hint(stringResource(R.string.feature_family_qr_hint))
                QrCode(InviteCode.link(space.inviteCode), Modifier.size(200.dp).align(Alignment.CenterHorizontally))
                // Wraps, so both buttons fit at a large font.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onShareCode) {
                        AppIcon(AppIcons.Share, null, Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.feature_family_share_code))
                    }
                    // Only the owner may replace the code (enforced by the security rules as well).
                    if (state.isOwner) {
                        TextButton(onClick = onResetCode) { Text(stringResource(R.string.feature_family_reset_code)) }
                    }
                }

                HorizontalDivider()
                SectionTitle(stringResource(R.string.feature_family_invite_by_email_title))
                EmailInviteField(onInviteEmail)
                space.invitedEmails.forEach { email ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(AppIcons.Mail, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            email,
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onCancelEmailInvite(email) }) {
                            AppIcon(AppIcons.Close, stringResource(R.string.feature_family_cancel_invite, email))
                        }
                    }
                }

                HorizontalDivider()
                SectionTitle(stringResource(R.string.feature_family_members_title))
                Hint(stringResource(R.string.feature_family_members_hint))
                space.members.forEach { member ->
                    val isYou = member.id == state.currentUserId
                    val isSpaceOwner = member.id == space.ownerId
                    MemberRow(
                        member = member,
                        isYou = isYou,
                        isSpaceOwner = isSpaceOwner,
                        // An adult decides who else is one. The owner is always an adult, and nobody
                        // changes their own role (the security rules say the same).
                        onSetRole = if (state.isAdult && !isYou && !isSpaceOwner) {
                            { onSetRole(member.id, if (member.role == MemberRole.ADULT) MemberRole.CHILD else MemberRole.ADULT) }
                        } else {
                            null
                        },
                        onRemove = if (state.isOwner && !isSpaceOwner) {
                            { onRemoveMember(member.id) }
                        } else {
                            null
                        },
                    )
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Black on white whatever the theme, because that is what cameras read best. */
@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    // Size 0 asks for the smallest code: one cell per module plus the quiet border.
    val matrix = remember(content) { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0) }
    val description = stringResource(R.string.feature_family_qr_description)
    Canvas(modifier.background(Color.White).semantics { contentDescription = description }) {
        val cell = size.minDimension / matrix.width
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                // Half a pixel wider, so rounding leaves no hairlines between dark cells.
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun EmailInviteField(onInviteEmail: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var invited by rememberSaveable { mutableStateOf<String?>(null) }
    // Set when Send is tried with a bad address: the field then explains itself until the address is fixed.
    var showError by rememberSaveable { mutableStateOf(false) }
    val isValid = normalizeEmail(email) != null
    val send = {
        if (isValid) {
            onInviteEmail(email)
            invited = normalizeEmail(email)
            email = ""
        }
        showError = !isValid
    }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it.take(254) },
        label = { Text(stringResource(R.string.feature_family_email_label)) },
        isError = showError && !isValid,
        supportingText = {
            val hint = stringResource(R.string.feature_family_email_hint)
            Text(
                when {
                    showError && !isValid -> stringResource(R.string.feature_family_email_invalid)
                    invited != null -> stringResource(R.string.feature_family_invited, invited.orEmpty()) + " " + hint
                    else -> hint
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(onClick = send, enabled = isValid) {
                AppIcon(AppIcons.Send, stringResource(R.string.feature_family_send_invite))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MemberRow(member: Member, isYou: Boolean, isSpaceOwner: Boolean, onSetRole: (() -> Unit)?, onRemove: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InitialAvatar(member.name)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                if (isYou) stringResource(R.string.feature_family_you, member.name) else member.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Hint(
                stringResource(
                    when {
                        isSpaceOwner -> R.string.feature_family_owner
                        member.role == MemberRole.ADULT -> R.string.feature_family_adult
                        else -> R.string.feature_family_child
                    },
                ),
            )
        }
        if (onSetRole != null) {
            val toAdult = member.role == MemberRole.CHILD
            val label = stringResource(if (toAdult) R.string.feature_family_make_adult else R.string.feature_family_make_child, member.name)
            TextButton(onClick = onSetRole, modifier = Modifier.semantics { contentDescription = label }) {
                Text(stringResource(if (toAdult) R.string.feature_family_adult else R.string.feature_family_child))
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                AppIcon(AppIcons.Close, stringResource(R.string.feature_family_remove_member, member.name))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@PreviewLightDark
@Composable
private fun SpaceDetailScreenPreview() {
    val today = 20_000L
    VaultAnythingTheme {
        SpaceDetailScreen(
            uiState = SpaceDetailUiState.Ready(
                space = Space(
                    id = "1",
                    name = "Our family",
                    ownerId = "u1",
                    inviteCode = "ABCD2345",
                    members = listOf(Member("u1", "Panos", MemberRole.ADULT), Member("u2", "Maria", MemberRole.ADULT), Member("u3", "Nikos")),
                    invitedEmails = listOf("grandma@example.com"),
                ),
                currentUserId = "u1",
                allDocuments = listOf(
                    Document("d1", "1", "u1", "Passport", DocumentKind.FILE, DocumentDetails(DocumentCategory.IDENTITY, expiresDay = today + 40, isFavorite = true)),
                    Document("d2", "1", "u3", "School report", DocumentKind.IMAGE, DocumentDetails(DocumentCategory.SCHOOL)),
                    Document("d3", "1", "u2", "Insurance portal", DocumentKind.LINK, DocumentDetails(DocumentCategory.CAR), link = "https://example.com"),
                ),
                today = today,
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenDocument = {},
            onAddDocument = {},
            onShowMember = {},
            onShowCategory = {},
            onShowFavorites = {},
            onRenameSpace = {},
            onShareCode = {},
            onResetCode = {},
            onInviteEmail = {},
            onCancelEmailInvite = {},
            onSetRole = { _, _ -> },
            onRemoveMember = {},
            onLeaveSpace = {},
            onDeleteSpace = {},
        )
    }
}

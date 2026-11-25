package com.kokouliss.vaultanything.feature.family

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.JoinResult
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.AddResult
import com.kokouliss.vaultanything.core.domain.repository.AuthRepository
import com.kokouliss.vaultanything.core.domain.repository.DocumentRepository
import com.kokouliss.vaultanything.core.domain.repository.EmailAuthResult
import com.kokouliss.vaultanything.core.domain.repository.SpaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class SpaceDetailViewModelTest {

    private val family = Space(
        "s1", "Family", "u1", "ABCD2345",
        listOf(Member("u1", "Panos", Role.ADULT), Member("u3", "Nikos")),
        emptyList(),
    )
    private val today = 20_000L
    private val passport = Document("d1", "s1", "u1", "Passport", DocumentKind.FILE, DocumentDetails(DocumentCategory.IDENTITY, expiresDay = today + 30))
    private val report = Document("d2", "s1", "u3", "School report", DocumentKind.IMAGE, DocumentDetails(DocumentCategory.SCHOOL, isFavorite = true))
    private val spaces = FakeSpaceRepository(family)
    private val documents = FakeDocumentRepository(listOf(passport, report))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(user: User) = SpaceDetailViewModel(SpaceDetailKey("s1"), spaces, documents, FakeAuthRepository(user))

    @Test
    fun `an adult sees everyone's documents, a child only their own`() = runTest {
        viewModel(User("u1", "Panos", null)).uiState.test {
            val state = expectMostRecentItem() as SpaceDetailUiState.Ready
            assertThat(state.allDocuments).containsExactly(passport, report)
            assertThat(state.visibleMembers.map { it.id }).containsExactly("u1", "u3")
            assertThat(documents.lastQuery).isEqualTo("u1" to true)
        }
        viewModel(User("u3", "Nikos", null)).uiState.test {
            val state = expectMostRecentItem() as SpaceDetailUiState.Ready
            assertThat(state.allDocuments).containsExactly(report)
            assertThat(state.visibleMembers.map { it.id }).containsExactly("u3")
            assertThat(documents.lastQuery).isEqualTo("u3" to false)
        }
    }

    @Test
    fun `filters narrow the list and favorites come first`() = runTest {
        val viewModel = viewModel(User("u1", "Panos", null))
        viewModel.uiState.test {
            assertThat((expectMostRecentItem() as SpaceDetailUiState.Ready).documents).containsExactly(report, passport).inOrder()

            viewModel.showMember("u3")
            assertThat((expectMostRecentItem() as SpaceDetailUiState.Ready).documents).containsExactly(report)

            viewModel.showMember(null)
            viewModel.showCategory(DocumentCategory.IDENTITY)
            assertThat((expectMostRecentItem() as SpaceDetailUiState.Ready).documents).containsExactly(passport)

            viewModel.showFavorites()
            val state = expectMostRecentItem() as SpaceDetailUiState.Ready
            assertThat(state.documents).containsExactly(report)
            assertThat(state.filter.category).isNull()
        }
    }

    @Test
    fun `documents that run out soon are listed whatever the filter`() = runTest {
        val viewModel = viewModel(User("u1", "Panos", null))
        viewModel.uiState.test {
            viewModel.showFavorites()
            val state = (expectMostRecentItem() as SpaceDetailUiState.Ready).copy(today = today)
            assertThat(state.expiring).containsExactly(passport)
            assertThat(state.addFor).isEqualTo("u1")
        }
    }

    @Test
    fun `a member who left takes their filter with them`() = runTest {
        val viewModel = viewModel(User("u1", "Panos", null))
        viewModel.uiState.test {
            viewModel.showMember("u3")
            spaces.space.value = family.copy(members = listOf(Member("u1", "Panos", Role.ADULT)))
            assertThat((expectMostRecentItem() as SpaceDetailUiState.Ready).filter.memberId).isNull()
        }
    }
}

private class FakeAuthRepository(user: User) : AuthRepository {
    override val currentUser: Flow<User?> = flowOf(user)
    override suspend fun signInWithGoogle(idToken: String) = true
    override suspend fun registerDevice() = Unit
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount(idToken: String) = true
    override suspend fun signInWithEmail(email: String, password: String) = EmailAuthResult.Success
    override suspend fun createAccount(name: String, email: String, password: String) = EmailAuthResult.Success
    override suspend fun sendPasswordReset(email: String) = EmailAuthResult.Success
    override suspend fun sendVerificationEmail() = true
    override suspend fun refreshEmailVerification() = Unit
    override suspend fun deleteAccountWithPassword(password: String) = true
    override val asksForName: Flow<Boolean> = flowOf(false)
    override suspend fun setName(name: String) = true
    override fun keepName() = Unit
}

private class FakeSpaceRepository(space: Space) : SpaceRepository {
    val space = MutableStateFlow<Space?>(space)
    override fun observeSpaces(): Flow<List<Space>> = space.map { listOfNotNull(it) }
    override fun observeInvitations(): Flow<List<Space>> = flowOf(emptyList())
    override fun observeSpace(spaceId: String): Flow<Space?> = space
    override suspend fun joinWithCode(code: String) = JoinResult.Joined
    override suspend fun createSpace(name: String) = Unit
    override suspend fun renameSpace(spaceId: String, name: String) = Unit
    override suspend fun acceptInvitation(spaceId: String) = Unit
    override suspend fun declineInvitation(spaceId: String) = Unit
    override suspend fun inviteEmail(spaceId: String, email: String) = Unit
    override suspend fun cancelEmailInvite(spaceId: String, email: String) = Unit
    override suspend fun setRole(spaceId: String, memberId: String, role: Role) = Unit
    override suspend fun removeMember(space: Space, memberId: String) = Unit
    override suspend fun resetInviteCode(space: Space) = Unit
    override suspend fun leaveSpace(spaceId: String) = Unit
    override suspend fun deleteSpace(space: Space) = Unit
}

private class FakeDocumentRepository(all: List<Document>) : DocumentRepository {
    private val documents = MutableStateFlow(all)
    var lastQuery: Pair<String, Boolean>? = null

    override fun observeDocuments(spaceId: String, userId: String, isAdult: Boolean): Flow<List<Document>> {
        lastQuery = userId to isAdult
        return documents.map { all -> if (isAdult) all else all.filter { it.ownerId == userId } }
    }

    override fun observeDocument(spaceId: String, documentId: String): Flow<Document?> =
        documents.map { all -> all.firstOrNull { it.id == documentId } }

    override suspend fun addLink(spaceId: String, ownerId: String, title: String, link: String, details: DocumentDetails) = "new"
    override suspend fun addFile(spaceId: String, ownerId: String, title: String, sourceUri: String, details: DocumentDetails): AddResult = AddResult.Added("new")
    override suspend fun update(spaceId: String, documentId: String, title: String, details: DocumentDetails) = Unit
    override suspend fun setFavorite(spaceId: String, documentId: String, favorite: Boolean) = Unit
    override suspend fun delete(document: Document) = Unit
    override suspend fun fetchFile(document: Document): String? = null
}

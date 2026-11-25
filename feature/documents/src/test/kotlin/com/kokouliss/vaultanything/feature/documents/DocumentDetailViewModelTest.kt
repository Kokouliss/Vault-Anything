package com.kokouliss.vaultanything.feature.documents

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.PinResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class DocumentDetailViewModelTest {

    private val family = Space(
        "s1", "Family", "u1", "ABCD2345",
        listOf(Member("u1", "Panos", Role.ADULT), Member("u2", "Maria", Role.ADULT), Member("u3", "Nikos")),
        emptyList(),
    )
    private val spaces = FakeSpaceRepository(family)
    private val documents = FakeDocumentRepository().apply {
        this.documents.value = listOf(
            Document("d1", "s1", "u3", "School report", DocumentKind.FILE, DocumentDetails()),
            Document("d2", "s1", "u2", "Blood test", DocumentKind.IMAGE, DocumentDetails()),
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val locks = FakeLockRepository(hasPin = true)
    private val activity = FakeActivityRepository()

    private fun viewModel(documentId: String, user: User) =
        DocumentDetailViewModel(DocumentDetailKey("s1", documentId), documents, spaces, FakeAuthRepository(user), locks, activity)

    @Test
    fun `an adult edits a child's document but not another adult's`() = runTest {
        val panos = User("u1", "Panos", null)
        viewModel("d1", panos).uiState.test {
            val state = expectMostRecentItem() as DocumentDetailUiState.Ready
            assertThat(state.ownerName).isEqualTo("Nikos")
            assertThat(state.canEdit).isTrue()
        }
        viewModel("d2", panos).uiState.test {
            assertThat((expectMostRecentItem() as DocumentDetailUiState.Ready).canEdit).isFalse()
        }
    }

    @Test
    fun `a locked document is sealed for its owner until the PIN is given, and opening another's is logged`() = runTest {
        documents.documents.value = documents.documents.value + Document("d3", "s1", "u2", "Diary", DocumentKind.FILE, DocumentDetails(), isLocked = true)
        val viewModel = viewModel("d3", User("u2", "Maria", null))
        viewModel.uiState.test {
            var state = expectMostRecentItem() as DocumentDetailUiState.Ready
            assertThat(state.isSealed).isTrue()
            assertThat(state.canLock).isTrue()
            assertThat(viewModel.unlock("1234")).isEqualTo(PinResult.Ok)
            state = expectMostRecentItem() as DocumentDetailUiState.Ready
            assertThat(state.isSealed).isFalse()
        }
        assertThat(activity.opened).isEmpty()
        viewModel("d1", User("u1", "Panos", null)).uiState.test { expectMostRecentItem() }
        assertThat(activity.opened).containsExactly("d1")
    }

    @Test
    fun `a link is made and listed until it is stopped, and a kept copy is remembered`() = runTest {
        val viewModel = viewModel("d1", User("u3", "Nikos", null))
        viewModel.shares.test {
            assertThat(expectMostRecentItem()).isEmpty()
            val share = viewModel.share(24)
            assertThat(share?.documentId).isEqualTo("d1")
            assertThat(expectMostRecentItem()).containsExactly(share)
            viewModel.unshare(share!!.id)
            assertThat(expectMostRecentItem()).isEmpty()
        }
        assertThat(viewModel.kept.value).isFalse()
        assertThat(viewModel.setKept(true)).isTrue()
        assertThat(viewModel.kept.value).isTrue()
        assertThat(documents.kept).containsExactly("d1")
    }

    @Test
    fun `a deleted document closes the screen`() = runTest {
        val viewModel = viewModel("d1", User("u3", "Nikos", null))
        viewModel.uiState.test {
            assertThat(expectMostRecentItem()).isInstanceOf(DocumentDetailUiState.Ready::class.java)
            viewModel.delete()
            documents.documents.value = emptyList()
            assertThat(expectMostRecentItem()).isEqualTo(DocumentDetailUiState.Gone)
        }
        assertThat(documents.deleted).containsExactly("d1")
    }
}

package com.kokouliss.vaultanything.feature.documents

import com.google.common.truth.Truth.assertThat
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentDetails
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Role
import com.kokouliss.vaultanything.core.domain.model.Space
import com.kokouliss.vaultanything.core.domain.model.User
import com.kokouliss.vaultanything.core.domain.repository.AddResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class EditDocumentViewModelTest {

    private val family = Space(
        "s1", "Family", "u1", "ABCD2345",
        listOf(Member("u1", "Panos", Role.ADULT), Member("u2", "Maria", Role.ADULT), Member("u3", "Nikos")),
        emptyList(),
    )
    private val spaces = FakeSpaceRepository(family)
    private val documents = FakeDocumentRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(key: EditDocumentKey, user: User = User("u1", "Panos", "panos@example.com")) =
        EditDocumentViewModel(key, documents, spaces, FakeAuthRepository(user))

    @Test
    fun `an adult may add for anyone in the family, a child only for themselves`() = runTest {
        val adult = viewModel(EditDocumentKey("s1", ownerId = "u3"))
        assertThat(adult.uiState.value.owners.map { it.id }).containsExactly("u1", "u2", "u3")
        assertThat(adult.uiState.value.ownerId).isEqualTo("u3")

        val child = viewModel(EditDocumentKey("s1", ownerId = "u1"), User("u3", "Nikos", null))
        assertThat(child.uiState.value.owners.map { it.id }).containsExactly("u3")
        // The child asked for Panos's documents; the space says no, so it is their own.
        assertThat(child.uiState.value.ownerId).isEqualTo("u3")
    }

    @Test
    fun `nothing is saved without a title and an attachment`() = runTest {
        val viewModel = viewModel(EditDocumentKey("s1"))
        assertThat(viewModel.uiState.value.canSave).isFalse()

        viewModel.setTitle("Passport")
        assertThat(viewModel.uiState.value.canSave).isFalse()

        viewModel.setLink("not a link")
        assertThat(viewModel.uiState.value.linkError).isTrue()
        assertThat(viewModel.uiState.value.canSave).isFalse()

        viewModel.setLink("gov.gr/passport")
        assertThat(viewModel.uiState.value.canSave).isTrue()
    }

    @Test
    fun `a picked file names the document and is saved with its details`() = runTest {
        val viewModel = viewModel(EditDocumentKey("s1"))
        viewModel.setFile("content://files/1", "Car insurance 2026.pdf")
        viewModel.setCategory(DocumentCategory.CAR)
        viewModel.setTags("insurance, car,insurance")
        viewModel.setExpiresDay(20_500)
        viewModel.setNote("  Renew in June  ")
        viewModel.setFavorite(true)

        assertThat(viewModel.uiState.value.title).isEqualTo("Car insurance 2026")
        viewModel.save()

        assertThat(viewModel.uiState.value.savedId).isEqualTo("new")
        val saved = documents.added.single()
        assertThat(saved.ownerId).isEqualTo("u1")
        assertThat(saved.details).isEqualTo(DocumentDetails(DocumentCategory.CAR, listOf("insurance", "car"), 20_500, "Renew in June", true))
    }

    @Test
    fun `a link is stored normalized`() = runTest {
        val viewModel = viewModel(EditDocumentKey("s1"))
        viewModel.setTitle("Tax portal")
        viewModel.setLink("  myaade.gov.gr ")
        viewModel.save()

        assertThat(documents.added.single().link).isEqualTo("https://myaade.gov.gr")
    }

    @Test
    fun `a file that is too large is reported and can be picked again`() = runTest {
        documents.addFileResult = AddResult.TooLarge
        val viewModel = viewModel(EditDocumentKey("s1"))
        viewModel.setFile("content://files/big", "video.mp4")
        viewModel.save()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo(SaveError.TooLarge)
        assertThat(state.attachment).isNull()
        assertThat(state.savedId).isNull()
        assertThat(state.isSaving).isFalse()
    }

    @Test
    fun `shared text becomes a link when it looks like one and a title otherwise`() = runTest {
        val link = viewModel(EditDocumentKey("s1", sharedText = "https://example.com/bill"))
        assertThat(link.uiState.value.attachment).isEqualTo(Attachment.Link("https://example.com/bill"))
        assertThat(link.uiState.value.title).isEmpty()

        val text = viewModel(EditDocumentKey("s1", sharedText = "Dentist receipt"))
        assertThat(text.uiState.value.attachment).isNull()
        assertThat(text.uiState.value.title).isEqualTo("Dentist receipt")
    }

    @Test
    fun `editing loads the document and writes only its details`() = runTest {
        documents.documents.value = listOf(
            Document(
                "d1", "s1", "u3", "School report", DocumentKind.FILE,
                DocumentDetails(DocumentCategory.SCHOOL, listOf("2026")), fileName = "report.pdf",
            ),
        )
        val viewModel = viewModel(EditDocumentKey("s1", documentId = "d1"))

        val loaded = viewModel.uiState.value
        assertThat(loaded.isNew).isFalse()
        assertThat(loaded.title).isEqualTo("School report")
        assertThat(loaded.existingFileName).isEqualTo("report.pdf")
        assertThat(loaded.tags).isEqualTo("2026")
        assertThat(loaded.canSave).isTrue()

        viewModel.setTitle("School report 2026")
        viewModel.save()

        assertThat(documents.updated.single().first).isEqualTo("d1")
        assertThat(documents.added).isEmpty()
        assertThat(viewModel.uiState.value.savedId).isEqualTo("d1")
    }
}

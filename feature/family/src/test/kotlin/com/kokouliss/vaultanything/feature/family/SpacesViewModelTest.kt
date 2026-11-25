package com.kokouliss.vaultanything.feature.family

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.kokouliss.vaultanything.core.domain.model.JoinResult
import com.kokouliss.vaultanything.core.domain.model.Member
import com.kokouliss.vaultanything.core.domain.model.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class SpacesViewModelTest {

    private val family = Space("s1", "Family", "u1", "ABCD2345", listOf(Member("u1", "Panos")), emptyList())
    private val flatmates = family.copy(id = "s2", name = "Flatmates")
    private val spaces = FakeSpaceRepository()
    private val auth = FakeAuthRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `shows the user's spaces and invitations`() = runTest {
        spaces.spaces.value = listOf(family)
        spaces.invitations.value = listOf(flatmates)

        SpacesViewModel(spaces, auth).uiState.test {
            assertThat(expectMostRecentItem()).isEqualTo(
                SpacesUiState.Ready(
                    userName = "Panos",
                    spaces = listOf(family),
                    invitations = listOf(flatmates),
                    join = JoinState.Idle,
                ),
            )
        }
    }

    @Test
    fun `a join result stays until the screen has shown it`() = runTest {
        spaces.joinResult = JoinResult.InvalidCode
        val viewModel = SpacesViewModel(spaces, auth)

        viewModel.uiState.test {
            viewModel.joinWithCode("WRONG")
            assertThat((expectMostRecentItem() as SpacesUiState.Ready).join)
                .isEqualTo(JoinState.Done(JoinResult.InvalidCode))

            viewModel.joinShown()
            assertThat((expectMostRecentItem() as SpacesUiState.Ready).join).isEqualTo(JoinState.Idle)
        }
    }

    @Test
    fun `a declined invitation goes at once and is written after keep`() = runTest {
        spaces.invitations.value = listOf(flatmates)
        val viewModel = SpacesViewModel(spaces, auth)

        viewModel.uiState.test {
            expectMostRecentItem()
            viewModel.declineInvitation("s2")
            assertThat((expectMostRecentItem() as SpacesUiState.Ready).invitations).isEmpty()
            assertThat(spaces.declined).isEmpty()

            viewModel.declines.keep(viewModel.declines.pending.value!!.token)
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(spaces.declined).containsExactly("s2")
    }

    @Test
    fun `a failing stream shows the error state and retry recovers`() = runTest {
        spaces.failSpaces = true
        val viewModel = SpacesViewModel(spaces, auth)

        viewModel.uiState.test {
            assertThat(expectMostRecentItem()).isEqualTo(SpacesUiState.Error)

            spaces.failSpaces = false
            viewModel.retry()

            assertThat(expectMostRecentItem()).isInstanceOf(SpacesUiState.Ready::class.java)
        }
    }
}

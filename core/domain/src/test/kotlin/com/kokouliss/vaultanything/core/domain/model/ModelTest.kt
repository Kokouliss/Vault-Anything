package com.kokouliss.vaultanything.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelTest {

    private val family = Space(
        id = "s1",
        name = "Family",
        ownerId = "dad",
        inviteCode = "ABCD2345",
        members = listOf(Member("dad", "Panos", Role.ADULT), Member("mum", "Maria", Role.ADULT), Member("kid", "Nikos", Role.CHILD)),
        invitedEmails = emptyList(),
    )

    @Test
    fun `adults see everyone and a child sees only themselves`() {
        assertThat(family.visibleMembers("mum").map { it.id }).containsExactly("dad", "mum", "kid").inOrder()
        assertThat(family.visibleMembers("kid").map { it.id }).containsExactly("kid")
        assertThat(family.visibleMembers("stranger")).isEmpty()
    }

    @Test
    fun `a parent manages a child's documents, but not the other parent's`() {
        assertThat(family.canManageDocumentsOf("dad", "kid")).isTrue()
        assertThat(family.canManageDocumentsOf("dad", "mum")).isFalse()
        assertThat(family.canManageDocumentsOf("kid", "kid")).isTrue()
        assertThat(family.canManageDocumentsOf("kid", "dad")).isFalse()
    }

    @Test
    fun `generated invite codes are valid`() {
        repeat(50) { assertThat(InviteCode.isValid(InviteCode.generate())).isTrue() }
    }

    @Test
    fun `invite codes are normalized the way people type them, and the link form is read back`() {
        assertThat(InviteCode.normalize(" abcd-2345 ")).isEqualTo("ABCD2345")
        assertThat(InviteCode.isValid("ABCD2340")).isFalse() // 0 is not in the alphabet
        assertThat(InviteCode.fromLink(InviteCode.link("ABCD2345"))).isEqualTo("ABCD2345")
        assertThat(InviteCode.fromLink("https://example.com/join/ABCD2345")).isNull()
    }

    @Test
    fun `emails are trimmed and lower-cased, and non-emails are rejected`() {
        assertThat(normalizeEmail("  Maria@Gmail.com ")).isEqualTo("maria@gmail.com")
        assertThat(normalizeEmail("maria")).isNull()
    }
}

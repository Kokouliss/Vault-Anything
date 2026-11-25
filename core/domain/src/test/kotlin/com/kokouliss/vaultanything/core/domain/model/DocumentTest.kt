package com.kokouliss.vaultanything.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentTest {

    private fun document(id: String, favorite: Boolean = false, updated: Long = 0, expiresDay: Long? = null, title: String = id) =
        Document(id, "s1", "dad", title, DocumentKind.FILE, DocumentDetails(isFavorite = favorite, expiresDay = expiresDay, tags = listOf("ΑΜΚΑ")), updatedAtMillis = updated)

    @Test
    fun `favorites come first, then the newest change`() {
        val shown = listOf(document("old", updated = 1), document("new", updated = 3), document("star", favorite = true, updated = 2)).sortedForDisplay()
        assertThat(shown.map { it.id }).containsExactly("star", "new", "old").inOrder()
    }

    @Test
    fun `expiring soon means within three months, soonest first, and includes what has expired`() {
        val today = 1000L
        val soon = listOf(document("passport", expiresDay = today + 60), document("id", expiresDay = today + 400), document("kteo", expiresDay = today - 3), document("none"))
            .expiringSoon(today)
        assertThat(soon.map { it.id }).containsExactly("kteo", "passport").inOrder()
        assertThat(document("kteo", expiresDay = today - 3).daysLeft(today)).isEqualTo(-3)
        assertThat(document("none").daysLeft(today)).isNull()
    }

    @Test
    fun `tags are split on commas, trimmed, cut to size and never repeated`() {
        assertThat(parseTags(" Passport, passport ,, ΑΜΚΑ, αμκα\nvisa ")).containsExactly("Passport", "ΑΜΚΑ", "visa").inOrder()
        assertThat(parseTags((1..20).joinToString(",") { "t$it" })).hasSize(MAX_TAGS)
        assertThat(parseTags("x".repeat(100)).single()).hasLength(MAX_TAG_LENGTH)
    }

    @Test
    fun `search ignores accents and case and looks at the tags too`() {
        val doc = document("1", title = "Διαβατήριο Νίκου")
        assertThat(doc.matches("διαβατηριο")).isTrue()
        assertThat(doc.matches("αμκα")).isTrue()
        assertThat(doc.matches("ταυτότητα")).isFalse()
        assertThat(doc.matches("  ")).isTrue()
    }

    @Test
    fun `links get https and anything that is not a web page is refused`() {
        assertThat(normalizeLink("gov.gr/wallet")).isEqualTo("https://gov.gr/wallet")
        assertThat(normalizeLink("HTTP://Example.com/a?b=1")).isEqualTo("http://Example.com/a?b=1")
        assertThat(normalizeLink("not a link")).isNull()
        assertThat(normalizeLink("mailto:a@b.com")).isNull()
    }
}

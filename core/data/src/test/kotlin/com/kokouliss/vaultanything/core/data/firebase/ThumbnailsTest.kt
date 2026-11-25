package com.kokouliss.vaultanything.core.data.firebase

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailsTest {

    @Test
    fun `a large picture is scaled so its longer side fits`() {
        assertThat(fitWithin(4000, 3000, 240)).isEqualTo(240 to 180)
        assertThat(fitWithin(3000, 4000, 240)).isEqualTo(180 to 240)
    }

    @Test
    fun `a small picture is never scaled up`() {
        assertThat(fitWithin(100, 80, 240)).isEqualTo(100 to 80)
    }

    @Test
    fun `a very thin picture keeps at least one pixel`() {
        assertThat(fitWithin(10_000, 2, 240)).isEqualTo(240 to 1)
    }

    @Test
    fun `a downloaded file never lands outside its folder`() {
        assertThat(safeFileName("../../etc/passwd")).isEqualTo(".._.._etc_passwd")
        assertThat(safeFileName("..")).isEqualTo("file")
        assertThat(safeFileName("   ")).isEqualTo("file")
        assertThat(safeFileName(null)).isEqualTo("file")
        assertThat(safeFileName("Passport 2026.pdf")).isEqualTo("Passport 2026.pdf")
    }
}

package top.supergeoff.dictee.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun `les tokens clairs suivent la spec`() {
        val p = Tokens.light
        assertEquals(0xFFF5F5F5, p.background); assertEquals(0xFFFFFFFF, p.surface)
        assertEquals(0xFF0A0A0A, p.text); assertEquals(0xFF6B6B6B, p.textSecondary); assertEquals(0xFFD7D7D7, p.border)
        assertEquals(0xFF171717, p.primary); assertEquals(0xFFFAFAFA, p.onPrimary)
        assertEquals(0xFFFEF3C7, p.reviewBackground); assertEquals(0xFF92400E, p.reviewText)
        assertEquals(0xFFFEE2E2, p.errorBackground); assertEquals(0xFF991B1B, p.errorText)
    }

    @Test
    fun `les tokens sombres suivent la spec et la maquette`() {
        val p = Tokens.dark
        assertEquals(0xFF0D0D0D, p.background); assertEquals(0xFF171717, p.surface)
        assertEquals(0xFFFAFAFA, p.text); assertEquals(0xFFB3B3B3, p.textSecondary); assertEquals(0x1AFFFFFFL, p.border)
        assertEquals(0xFFE5E5E5, p.primary); assertEquals(0xFF171717, p.onPrimary)
        assertEquals(0x8078350F, p.reviewBackground); assertEquals(0xFFFBBF24, p.reviewText)
        assertEquals(0x807F1D1D, p.errorBackground); assertEquals(0xFFFCA5A5, p.errorText)
    }
}

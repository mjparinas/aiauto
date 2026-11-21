package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @Test fun readsTheApisMinorUnits() {
        assertEquals(25.0, Money.fromCents("2500"))
        assertEquals(0.5, Money.fromCents("50"))
        // The amount behind the bug report: 39 cents, not $39.
        assertEquals(0.39, Money.fromCents("39"))
        assertEquals(0.0, Money.fromCents("0"))
        assertNull(Money.fromCents("12.50"))
        assertNull(Money.fromCents(""))
    }

    @Test fun writesBudgetsInMinorUnits() {
        assertEquals("4600", Money.toCents(46.0))
        assertEquals("1099", Money.toCents(10.999))
        assertEquals("29", Money.toCents(0.29))
        assertEquals("1", Money.toCents(0.0))
    }
}

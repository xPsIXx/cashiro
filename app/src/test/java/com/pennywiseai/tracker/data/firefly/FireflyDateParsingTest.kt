package com.pennywiseai.tracker.data.firefly

import android.content.Context
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * QA: [FireflyClient.parseDate] feeds the budget-limit and recurrence floors.
 * The v6 OpenAPI mixes `format: date` (recurrence first_date, example
 * "2026-03-31") with `format: date-time` (budget-limit start/end, example
 * "2026-03-01T00:00:00+00:00"), and real instances emit date-time for journal
 * dates too. The old strict `LocalDate.parse` silently DROPPED every row
 * arriving in the other shape (mapNotNull on a null date) — budgets and
 * recurrences vanished without any error. These tests pin that all date
 * shapes land on the right LocalDate and only genuine garbage returns null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyDateParsingTest {

    private lateinit var client: FireflyClient

    @Before
    fun setUp() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        client = FireflyClient(
            context,
            mockk<FireflySyncDao>(relaxed = true),
            mockk<BudgetRepository>(relaxed = true),
            mockk<SubscriptionRepository>(relaxed = true),
            mockk<TransactionRepository>(relaxed = true),
            DiagnosticLogger(context)
        )
    }

    @Test
    fun dateOnlyStringsParse() {
        assertEquals(LocalDate.parse("2026-03-31"), client.parseDate("2026-03-31"))
    }

    @Test
    fun offsetDateTimeStringsParseToTheirLocalDate() {
        // The exact budget-limit examples from the official v6 spec.
        assertEquals(LocalDate.parse("2026-03-01"), client.parseDate("2026-03-01T00:00:00+00:00"))
        assertEquals(LocalDate.parse("2026-03-31"), client.parseDate("2026-03-31T23:59:59+00:00"))
        // Offset that rolls past midnight UTC stays on its LOCAL date.
        assertEquals(LocalDate.parse("2026-03-31"), client.parseDate("2026-03-31T23:59:59+02:00"))
        assertEquals(LocalDate.parse("2026-04-01"), client.parseDate("2026-04-01T00:00:01+00:00"))
    }

    @Test
    fun instantStringsParse() {
        assertEquals(LocalDate.parse("2026-03-15"), client.parseDate("2026-03-15T12:00:00Z"))
    }

    @Test
    fun garbageAndNullsStayNull() {
        assertNull(client.parseDate(null))
        assertNull(client.parseDate(""))
        assertNull(client.parseDate("   "))
        assertNull(client.parseDate("not-a-date"))
        assertNull(client.parseDate("2026-13-45"))
    }
}

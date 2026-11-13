package com.pennywiseai.tracker.data.firefly

import android.content.Context
import com.pennywiseai.tracker.data.database.entity.BudgetPeriodType
import com.pennywiseai.tracker.data.diagnostic.DiagnosticLogger
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.repository.SubscriptionRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * L4: period-type mapping for imported Firefly budget limits.
 *
 * The local enum only has WEEKLY / MONTHLY / CUSTOM. The fix prioritises the
 * budget's DECLARED auto-budget cadence (v6 BudgetProperties.auto_budget_period)
 * over the day-span guess — but only when the observed limit span agrees with
 * it, so a "monthly"-declared budget on a quarterly span stays an honest
 * CUSTOM instead of a silent lie, and a 27-day fiscal month declared "monthly"
 * is no longer demoted to CUSTOM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireflyBudgetPeriodInferenceTest {

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

    private fun type(declared: String?, start: String, end: String) =
        client.inferPeriodType(declared, LocalDate.parse(start), LocalDate.parse(end))

    @Test
    fun declaredWeeklyOnSevenDaySpanIsWeekly() {
        assertEquals(BudgetPeriodType.WEEKLY, type("weekly", "2026-03-02", "2026-03-08"))
    }

    @Test
    fun declaredMonthlyRescuesTwentySevenDayFiscalMonth() {
        // Feb 2 -> Feb 28 2026 = 27 days: span-only heuristic said CUSTOM;
        // the declared cadence (span agrees) now says MONTHLY.
        assertEquals(BudgetPeriodType.MONTHLY, type("monthly", "2026-02-02", "2026-02-28"))
        // 32-day fiscal month variant is also covered by the declared band.
        assertEquals(BudgetPeriodType.MONTHLY, type("monthly", "2026-03-01", "2026-04-01"))
    }

    @Test
    fun declaredCadenceLosesWhenSpanDisagrees() {
        // "monthly" cadence but a 91-day limit span -> the span is the truth.
        assertEquals(BudgetPeriodType.CUSTOM, type("monthly", "2026-01-01", "2026-04-01"))
        // "weekly" cadence but a 31-day span -> disagreeing declaration falls
        // back to the span heuristic: 31 days reads as MONTHLY, not CUSTOM.
        assertEquals(BudgetPeriodType.MONTHLY, type("weekly", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun quarterlyAndAnnualSpansAreHonestCustoms() {
        assertEquals(BudgetPeriodType.CUSTOM, type("quarterly", "2026-01-01", "2026-04-01"))
        assertEquals(BudgetPeriodType.CUSTOM, type("yearly", "2026-01-01", "2026-12-31"))
        assertEquals(BudgetPeriodType.CUSTOM, type(null, "2026-01-01", "2026-04-01"))
        assertEquals(BudgetPeriodType.CUSTOM, type(null, "2026-01-01", "2026-12-31"))
    }

    @Test
    fun spanOnlyFallbackKeepsPreviousBehaviour() {
        assertEquals(BudgetPeriodType.WEEKLY, type(null, "2026-03-02", "2026-03-08"))
        assertEquals(BudgetPeriodType.MONTHLY, type(null, "2026-03-01", "2026-03-31"))
        // Span-only 27-day range was CUSTOM before and stays CUSTOM — only a
        // DECLARED cadence may rescue edge spans.
        assertEquals(BudgetPeriodType.CUSTOM, type(null, "2026-02-02", "2026-02-28"))
    }

    @Test
    fun declaredCadenceIsCaseAndWhitespaceTolerant() {
        assertEquals(BudgetPeriodType.WEEKLY, type(" Weekly ", "2026-03-02", "2026-03-08"))
        assertEquals(BudgetPeriodType.MONTHLY, type("MONTHLY", "2026-03-01", "2026-03-30"))
    }
}

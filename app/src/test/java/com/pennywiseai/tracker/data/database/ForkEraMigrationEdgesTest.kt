package com.pennywiseai.tracker.data.database

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression for the fork-era (Cashiro schemas 47-52) -> consolidated schema 67 direct migration
 * edges. These previously shipped three crash classes on existing installs:
 *   1. ``x`` double-backtick identifiers (SQLite reads them as an EMPTY identifier ->
 *      SQLiteSyntaxException at first execSQL on every 47-52 upgrade),
 *   2. "no such column: excluded_from_analytics" — the 49/50 edges hardcoded a copy column list
 *      the source table does not have (the column arrived at 50->51),
 *   3. "duplicate column name: match_type" — the 48..52 edges re-ADDed a column the fork chain
 *      had already applied (51->52).
 * All were caught only by executing the real SQL against reconstructed fork-era schemas; the
 * mockk-based MigrationSqlCompatibilityTest cannot see them because it never executes statements.
 *
 * The seed fixtures (app/src/test/resources/migrations/seed_47.sql, seed_52.sql) are generated
 * from the Room schema exports (app/schemas/.../47.json / 52.json) — 47 carries the legacy
 * firefly_* state columns, 52 does not. If those exports change, regenerate the fixtures
 * (see tools/migration_harness.py, the python mirror used during the fix).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForkEraMigrationEdgesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun openDb(): SupportSQLiteDatabase =
        FrameworkSQLiteDatabase(
            RoomDatabase.Builder.createOpenHelper(
                context, File(context.noBackupFilesDir, DB_NAME), null, true,
            ).getOrOpenWritableDatabase()
        )

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> {
        val cursor = query("PRAGMA table_info(`$table`)")
        try {
            val idx = cursor.getColumnIndex("name")
            return generateSequence { if (cursor.moveToNext()) cursor.getString(idx) else null }.toSet()
        } finally {
            cursor.close()
        }
    }

    private fun SupportSQLiteDatabase.tables(): Set<String> {
        val cursor = query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
        try {
            return generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
        } finally {
            cursor.close()
        }
    }

    private fun fixtureLines(version: Int): List<String> {
        val url = checkNotNull(javaClass.classLoader?.getResource("migrations/seed_$version.sql")) {
            "missing test fixture migrations/seed_$version.sql"
        }
        return File(url.file).readLines().filter { it.isNotBlank() && !it.startsWith("--") }
    }

    /** Column names of the CREATE TABLE <table> statement in the fixture. */
    private fun fixtureColumns(lines: List<String>, table: String): List<String> {
        val line = lines.first { it.startsWith("""CREATE TABLE "$table"""") }
        return Regex("\"(\\w+)\"").findAll(line.substringAfter("(")).map { it.groupValues[1] }.toList()
    }

    private fun seed(con: SupportSQLiteDatabase, version: Int) {
        val lines = fixtureLines(version)
        lines.forEach(con::execSQL)
        val txDdl = lines.first { it.startsWith("""CREATE TABLE "transactions"""") }
        // Column definition -> SQL literal: NOT NULL TEXT becomes a sentinel, NOT NULL INTEGER 0,
        // nullable columns NULL. Dates/hash get recognisable values so survival can be asserted.
        val colDefs = Regex("\"(\\w+)\"\\s+(INTEGER|TEXT)([^,\")]+)?").findAll(txDdl).toList()
        val cols = colDefs.map { it.groupValues[1] }
        val values = colDefs.map { m ->
            val (name, type, rest) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            when {
                name == "id" -> "1"
                name == "transaction_hash" -> "'h1'"
                !rest.contains("NOT NULL") -> "NULL"
                name.contains("date") || name.endsWith("_at") || name == "timestamp" -> "'2026-01-01'"
                type == "INTEGER" -> "0"
                else -> "'x'"
            }
        }
        con.execSQL(
            "INSERT INTO transactions (${cols.joinToString(", ") { "`$it`" }}) VALUES (${values.joinToString(", ")})"
        )
    }

    private fun runEdge(edge: RoomDatabase.Migration, con: SupportSQLiteDatabase) {
        edge.migrate(con)
        edge.migrate(con) // idempotency: a half-completed or repeated run must not crash
    }

    @Test
    fun migration47To67UpgradesLegacyFireflyInstallWithoutCrash() {
        val con = openDb()
        try {
            seed(con, 47)
            assertTrue("fixture must carry legacy firefly state", con.columns("transactions").contains("firefly_synced_at"))
            // Mark the seeded row as already synced on Firefly before the upgrade.
            con.execSQL("UPDATE transactions SET firefly_synced_at = '2026-01-01T00:00:00', firefly_external_id = 'pennywise-1'")

            runEdge(PennyWiseDatabase.MIGRATION_47_67, con)

            assertConsolidated(con)
            assertFalse("legacy firefly state columns must be dropped", con.columns("transactions").any { it.startsWith("firefly_") })
            // Sync state must be staged for the sidecar backfill, not silently lost (H1 aggravator).
            val staged = con.query("SELECT external_id, synced_at FROM firefly_sync_state_staging WHERE transaction_id = 1")
            assertTrue("already-synced transaction must be staged", staged.moveToFirst())
            assertEquals("pennywise-1", staged.getString(0))
            staged.close()
            assertEquals("seeded transaction must survive the rebuild",
                1, con.query("SELECT COUNT(*) FROM transactions WHERE transaction_hash = 'h1'").let { c -> c.moveToFirst(); c.getInt(0) })
        } finally {
            con.close()
        }
    }

    @Test
    fun migration52To67UpgradesPostFireflyInstallWithoutCrash() {
        val con = openDb()
        try {
            seed(con, 52)
            assertFalse("52 fixture must not carry firefly state", con.columns("transactions").contains("firefly_synced_at"))
            assertTrue("52 fixture must already carry match_type (added at 51->52)",
                con.columns("budget_categories").contains("match_type"))

            runEdge(PennyWiseDatabase.MIGRATION_52_67, con)

            assertConsolidated(con)
            val tables = con.query("SELECT name FROM sqlite_master WHERE type=\'table\' AND name=\'firefly_sync_state_staging\'")
            assertFalse("no staging table when no legacy state existed", tables.moveToFirst())
            tables.close()
        } finally {
            con.close()
        }
    }

    /** Every direct edge must land on the full consolidated v67 shape, idempotently. */
    private fun assertConsolidated(con: SupportSQLiteDatabase) {
        val tables = con.tables()
        assertEquals("all v67 tables must exist", true, V67_TABLES.all { it in tables })

        for ((table, expected) in V67_KEY_TABLE_COLUMNS) {
            val have = con.columns(table)
            val missing = expected - have
            assertTrue("$table missing v67 columns: $missing", missing.isEmpty())
        }

        // Guarded columns that fork installs may lack on non-rebuilt tables.
        assertTrue(con.columns("account_balances").contains("alias"))
        assertTrue(con.columns("account_balances").contains("lowBalanceThreshold"))
        assertTrue(con.columns("categories").contains("icon"))
        assertTrue(con.columns("categories").contains("parent_id"))
        assertTrue(con.columns("categories").contains("system_name"))
        assertTrue(con.columns("categories").contains("is_hidden"))
        assertTrue(con.columns("budget_categories").contains("match_type"))
        assertTrue(con.columns("budget_category_month_snapshots").contains("match_type"))
        assertTrue(con.columns("transactions").contains("excluded_from_analytics"))

        // The transaction_hash unique index must have been recreated after the rebuild.
        val indexes = con.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_transactions_transaction_hash'")
        assertTrue("index_transactions_transaction_hash missing", indexes.moveToFirst())
        indexes.close()
    }

    companion object {
        private const val DB_NAME = "migration_edge_test.db"

        private val V67_TABLES = setOf(
            "transactions", "subscriptions", "chat_messages", "merchant_mappings", "merchant_aliases",
            "categories", "account_balances", "unrecognized_sms", "cards", "transaction_rules",
            "rule_applications", "exchange_rates", "budgets", "budget_categories", "budget_month_snapshots",
            "budget_category_month_snapshots", "transaction_splits", "bank_notifications", "loans",
            "transaction_groups", "profiles", "tags", "transaction_tag_cross_ref", "recurring_transactions",
            "webhook_profiles", "webhook_logs", "webhook_cursors", "webhook_delivered_transactions",
            "goals", "goal_contributions", "loan_shares",
        )

        private val V67_KEY_TABLE_COLUMNS = mapOf(
            "transactions" to setOf("id", "amount", "merchant_name", "category", "transaction_type", "date_time", "description", "sms_body", "bank_name", "sms_sender", "account_number", "balance_after", "transaction_hash", "is_recurring", "is_deleted", "excluded_from_analytics", "created_at", "updated_at", "currency", "from_account", "to_account", "from_bank_name", "to_bank_name", "reference", "loan_id", "loan_contribution", "lent_shares_total", "receipt_path", "budget_category", "budget_impact_type", "group_id", "profile_id"),
            "budgets" to setOf("id", "name", "limit_amount", "period_type", "start_date", "end_date", "weekday_anchor", "month_anchor", "currency", "is_active", "include_all_categories", "color", "group_type", "display_order", "created_at", "updated_at"),
            "subscriptions" to setOf("id", "merchant_name", "amount", "next_payment_date", "state", "bank_name", "account_last4", "umn", "category", "sms_body", "created_at", "updated_at", "currency", "direction", "billing_cycle", "last_paid_at"),
        )
    }
}

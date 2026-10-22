package com.langkraft.data.database

import app.cash.sqldelight.Query
import com.langkraft.data.storage.DatabaseClient
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowserInvalidationTest {
    private class Client {
        var listener: (() -> Unit)? = null
        var publications = 0
        var closed = false
        var failCommit = false
        val requests = mutableListOf<String>()
        val external = js("({})").unsafeCast<DatabaseClient>()
        init {
            external.asDynamic().setChangeListener = { callback: (() -> Unit)? -> listener = callback }
            external.asDynamic().publishChanges = { publications++ }
            external.asDynamic().close = { closed = true }
            external.asDynamic().request = { action: String, _: String?, _: Array<Any?> ->
                requests.add(action)
                if (action == "end_transaction" && failCommit) Promise.reject(js("new Error('Commit failed')"))
                else Promise.resolve(js("[[0]]"))
            }
        }
    }

    @Test fun externalHintInvalidatesOnceWithoutSqlOrEcho() {
        val client = Client()
        val driver = BrowserSqlDriver(client.external)
        var changes = 0
        val listener = object : Query.Listener { override fun queryResultsChanged() { changes++ } }
        driver.addListener("Content", "Vocabulary", listener = listener)
        client.listener!!()
        assertEquals(1, changes)
        assertEquals(emptyList(), client.requests)
        assertEquals(0, client.publications)
        driver.close()
    }

    @Test fun externalHintWaitsForLocalTransaction() = runTest {
        val client = Client()
        val driver = BrowserSqlDriver(client.external)
        var changes = 0
        driver.addListener("Content", listener = object : Query.Listener {
            override fun queryResultsChanged() { changes++ }
        })
        AppDatabase(driver).transaction {
            client.listener!!()
            client.listener!!()
            assertEquals(0, changes)
        }
        assertEquals(1, changes)
        assertEquals(0, client.publications)
        driver.close()
    }

    @Test fun rollbackDoesNotPublishButCommitDoes() = runTest {
        val client = Client()
        val driver = BrowserSqlDriver(client.external)
        val database = AppDatabase(driver)
        assertFailsWith<IllegalStateException> {
            database.transaction {
                driver.notifyListeners("Content")
                error("Abort")
            }
        }
        assertEquals(0, client.publications)
        database.transaction {
            driver.notifyListeners("Content")
            driver.notifyListeners("Vocabulary")
            assertEquals(0, client.publications)
        }
        assertEquals(1, client.publications)
        driver.close()
    }

    @Test fun failedCommitRollsBackWithoutPublication() = runTest {
        val client = Client().apply { failCommit = true }
        val driver = BrowserSqlDriver(client.external)
        assertFailsWith<Throwable> {
            AppDatabase(driver).transaction { driver.notifyListeners("Content") }
        }
        assertEquals(0, client.publications)
        assertEquals("rollback_transaction", client.requests.last())
        driver.close()
    }

    @Test fun closeDetachesAndIgnoresLateHints() {
        val client = Client()
        val driver = BrowserSqlDriver(client.external)
        var changes = 0
        driver.addListener("Content", listener = object : Query.Listener {
            override fun queryResultsChanged() { changes++ }
        })
        val lateHint = client.listener!!
        driver.close()
        lateHint()
        driver.notifyListeners("Content")
        assertEquals(0, changes)
        assertEquals(null, client.listener)
        assertEquals(0, client.publications)
        assertEquals(true, client.closed)
    }
}

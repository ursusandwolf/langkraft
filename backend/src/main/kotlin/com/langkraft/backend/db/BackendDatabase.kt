package com.langkraft.backend.db

import org.jetbrains.exposed.sql.Database
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.sql.Connection
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.UUID

/** SQLite bootstrap, independent of DI and of Exposed's evolving table definitions. */
object BackendDatabase {
    private data class Migration(val version: Int, val name: String) {
        val sql = checkNotNull(javaClass.getResource("/db/migrations/V${version}__$name.sql")) {
            "Missing backend migration $version"
        }.readText().replace("\r\n", "\n")
        val checksum = MessageDigest.getInstance("SHA-256").digest(sql.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val migrations = listOf(
        Migration(1, "core"), Migration(2, "srs_and_jobs"), Migration(3, "sync_cursor_and_tombstones")
    )
    private val legacyColumns = setOf("lapse_count", "tags", "server_updated", "deleted")
    private const val history = "backend_schema_history"

    fun open(file: File): Database {
        migrate(file)
        return Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC")
    }

    fun migrate(file: File) {
        Class.forName("org.sqlite.JDBC")
        val absolute = file.absoluteFile
        check(absolute.parentFile.isDirectory || absolute.parentFile.mkdirs()) { "Cannot create backend data directory" }
        // Serializes backup and migration across processes. Runtime sharing of a data dir is unsupported.
        FileChannel.open(File(absolute.path + ".migration.lock").toPath(), CREATE, WRITE).use { channel ->
            checkNotNull(channel.tryLock()) { "Backend database migration is already running" }.use {
                DriverManager.getConnection("jdbc:sqlite:${absolute.path}").use { connection ->
                    connection.execute("PRAGMA busy_timeout = 5000")
                    connection.execute("PRAGMA foreign_keys = ON")
                    val version = readVersion(connection)
                    validate(connection, version)
                    if (version == migrations.size) return
                    if (tables(connection).isNotEmpty()) backup(connection, absolute)
                    connection.execute("BEGIN IMMEDIATE")
                    try {
                        // No schema or data is changed until the preflight and backup succeed.
                        connection.execute("""CREATE TABLE IF NOT EXISTS $history (
                            version INTEGER PRIMARY KEY NOT NULL,
                            name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at BIGINT NOT NULL
                        )""")
                        migrations.filter { it.version > version }.forEach { migration ->
                            apply(connection, migration, adoptLegacy = version == 0)
                            connection.prepareStatement("INSERT INTO $history VALUES (?, ?, ?, ?)").use { statement ->
                                statement.setInt(1, migration.version)
                                statement.setString(2, migration.name)
                                statement.setString(3, migration.checksum)
                                statement.setLong(4, System.currentTimeMillis())
                                statement.executeUpdate()
                            }
                        }
                        validate(connection, migrations.size)
                        connection.execute("COMMIT")
                    } catch (failure: Throwable) {
                        runCatching { connection.execute("ROLLBACK") }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                }
            }
        }
    }

    private fun backup(connection: Connection, file: File) {
        val target = File(file.path + ".before-backend-schema-${migrations.size}-${UUID.randomUUID()}.bak")
        // VACUUM INTO produces a consistent snapshot, including committed WAL data.
        // A unique name preserves previous backups and avoids reusing a stale retry snapshot.
        connection.execute("VACUUM INTO '${target.absolutePath.replace("'", "''")}'")
    }

    private fun readVersion(connection: Connection): Int {
        if (history !in tables(connection)) return 0
        check(columns(connection, history) == mapOf(
            "version" to Column("INTEGER", true, 1),
            "name" to Column("TEXT", true, 0),
            "checksum" to Column("TEXT", true, 0),
            "applied_at" to Column("INTEGER", true, 0)
        )) { "Unrecognized backend schema history table" }
        val applied = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version, name, checksum FROM $history ORDER BY version").use { rows ->
                buildList {
                    while (rows.next()) {
                        val version = rows.getInt(1)
                        val migration = migrations.getOrNull(version - 1)
                        check(migration != null && version == size + 1) { "Unsupported backend schema history" }
                        check(rows.getString(2) == migration.name && rows.getString(3) == migration.checksum) {
                            "Backend migration $version checksum mismatch"
                        }
                        add(version)
                    }
                }
            }
        }
        check(applied.isNotEmpty()) { "Empty backend schema history" }
        return applied.last()
    }

    private fun apply(connection: Connection, migration: Migration, adoptLegacy: Boolean) {
        migration.sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { sql ->
            // Known unversioned builds already have some additive columns. All other SQL runs verbatim.
            val addition = Regex("ALTER TABLE (\\w+) ADD COLUMN (\\w+).*", RegexOption.DOT_MATCHES_ALL).matchEntire(sql)
            if (!adoptLegacy || addition == null || addition.groupValues[2] !in columns(connection, addition.groupValues[1])) {
                connection.execute(sql)
            }
        }
    }

    private data class Column(val affinity: String, val required: Boolean, val primaryKey: Int)

    private fun affinity(type: String): String = when {
        "INT" in type -> "INTEGER"
        listOf("CHAR", "CLOB", "TEXT").any { it in type } -> "TEXT"
        listOf("REAL", "FLOA", "DOUB").any { it in type } -> "REAL"
        type.isEmpty() || "BLOB" in type -> "BLOB"
        else -> "NUMERIC"
    }

    private fun columns(connection: Connection, table: String): Map<String, Column> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info('$table')").use { rows ->
                buildMap {
                    while (rows.next()) {
                        val primary = rows.getInt("pk")
                        put(rows.getString("name"), Column(affinity(rows.getString("type").uppercase()),
                            rows.getInt("notnull") != 0 || primary != 0, primary))
                    }
                }
            }
        }

    private fun tables(connection: Connection): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*'").use { rows ->
            buildSet { while (rows.next()) add(rows.getString(1)) }
        }
    }

    private val expected by lazy {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            migrations.forEach { apply(connection, it, adoptLegacy = false) }
            tables(connection).associateWith { columns(connection, it) }
        }
    }

    private fun validate(connection: Connection, version: Int) {
        val legacy = version == 0
        val expected = if (legacy || version == migrations.size) expected else {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { reference ->
                migrations.take(version).forEach { apply(reference, it, adoptLegacy = false) }
                tables(reference).associateWith { columns(reference, it) }
            }
        }
        val actualTables = tables(connection) - history
        if (legacy && actualTables.isEmpty()) return
        check(actualTables == expected.keys || (legacy && actualTables == expected.keys - "ingestion_jobs")) {
            "Unrecognized backend database tables"
        }
        for (table in actualTables) {
            val actual = columns(connection, table)
            val optional = if (legacy && table == "vocabulary_sync") legacyColumns else emptySet()
            check(actual.keys - optional == expected.getValue(table).keys - optional &&
                actual.all { (name, column) -> expected.getValue(table)[name] == column }) {
                "Unrecognized backend schema for $table"
            }
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list('users')").use { rows ->
                val uniqueIndexes = buildList { while (rows.next()) if (rows.getInt("unique") == 1 && rows.getInt("partial") == 0) add(rows.getString("name")) }
                check(uniqueIndexes.any { index ->
                    connection.createStatement().use { indexStatement ->
                        indexStatement.executeQuery("PRAGMA index_info('${index.replace("'", "''")}')").use { indexRows ->
                            buildList { while (indexRows.next()) add(indexRows.getString("name")) } == listOf("email")
                        }
                    }
                }) { "Backend users.email must be unique" }
            }
            statement.executeQuery("PRAGMA foreign_key_list('vocabulary_sync')").use { rows ->
                var ownerReference = false
                while (rows.next()) {
                    if (rows.getString("table") == "users" && rows.getString("from") == "user_id" && rows.getString("to") == "id") ownerReference = true
                }
                check(ownerReference) { "Backend vocabulary owner foreign key is missing" }
            }
            statement.executeQuery("PRAGMA quick_check").use { rows ->
                check(rows.next() && rows.getString(1) == "ok" && !rows.next()) { "Backend database integrity check failed" }
            }
            statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
                check(!rows.next()) { "Backend database has orphaned references" }
            }
        }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }
}

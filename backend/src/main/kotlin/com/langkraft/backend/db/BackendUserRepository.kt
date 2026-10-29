package com.langkraft.backend.db

import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class BackendUserRepository(private val db: org.jetbrains.exposed.sql.Database) {
    fun findById(id: String) = transaction(db) {
        Users.select { Users.id eq id }.map { row ->
            UserRow(row[Users.id], row[Users.email], row[Users.passwordHash], row[Users.displayName])
        }.singleOrNull()
    }

    fun createUser(email: String, passwordHash: String, displayName: String): String? = transaction(db) {
        val userId = UUID.randomUUID().toString()
        val inserted = Users.insertIgnore {
            it[id] = userId
            it[this.email] = email
            it[this.passwordHash] = passwordHash
            it[this.displayName] = displayName
        }
        if (inserted.insertedCount == 1) userId else null
    }

    fun findByEmail(email: String) = transaction(db) {
        Users.select { Users.email eq email }
            .map {
                UserRow(
                    id = it[Users.id],
                    email = it[Users.email],
                    passwordHash = it[Users.passwordHash],
                    displayName = it[Users.displayName]
                )
            }.singleOrNull()
    }
}

data class UserRow(
    val id: String,
    val email: String,
    val passwordHash: String,
    val displayName: String
)

package com.langkraft.backend.db

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class BackendUserRepository {

    fun createUser(email: String, passwordHash: String, displayName: String): String = transaction {
        val userId = UUID.randomUUID().toString()
        Users.insert {
            it[id] = userId
            it[this.email] = email
            it[this.passwordHash] = passwordHash
            it[this.displayName] = displayName
        }
        userId
    }

    fun findByEmail(email: String) = transaction {
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

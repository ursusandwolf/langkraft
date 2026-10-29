package com.langkraft.data.profile

import com.langkraft.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID

fun desktopDataRoot() = File(System.getenv("LANGKRAFT_DATA_DIR") ?: File(System.getProperty("user.home"), ".langkraft").path)

/** Registry writes are atomic and locked across processes; data never moves between profiles. */
class JvmLocalProfileStore(root: File) : LocalProfileStore {
    private val root = root.toPath().toAbsolutePath().normalize()
    private val registry = this.root.resolve("profiles.json")
    @Serializable private data class Registry(val version: Int = 1, val selected: ProfileId = ProfileId(""), val profiles: List<LocalProfile> = emptyList())

    override suspend fun list(): List<LocalProfile> = locked { listOf(defaultLocalProfile) + read().profiles }
    override suspend fun selected(): ProfileId = locked { read().selected }

    override suspend fun create(name: String): LocalProfile = locked {
        val profile = LocalProfile(ProfileId("local-${UUID.randomUUID()}"), name.trim())
        val state = read()
        write(state.copy(profiles = state.profiles + profile))
        profile
    }

    override suspend fun select(id: ProfileId) = locked {
        val state = read()
        require(id.value.isEmpty() || state.profiles.any { it.id == id }) { "Unknown local profile" }
        write(state.copy(selected = id))
    }

    fun directory(id: ProfileId): File {
        if (id.value.isEmpty()) return root.toFile()
        require(id.value.startsWith("local-")) { "Unsupported desktop profile" }
        val parent = root.resolve("profiles")
        val target = parent.resolve(id.value).normalize()
        check(target.parent == parent && !Files.isSymbolicLink(parent) && !Files.isSymbolicLink(target)) { "Unsafe profile directory" }
        return target.toFile()
    }

    private fun read(): Registry {
        if (!Files.exists(registry)) return Registry()
        check(!Files.isSymbolicLink(registry) && Files.size(registry) <= 1_048_576) { "Invalid profile registry" }
        val state = Json.decodeFromString<Registry>(Files.readString(registry))
        check(state.version == 1 && state.profiles.size <= 100 && state.profiles.all { it.id.value.startsWith("local-") } &&
            state.profiles.map { it.id }.toSet().size == state.profiles.size &&
            (state.selected.value.isEmpty() || state.profiles.any { it.id == state.selected })) { "Unsupported profile registry" }
        return state
    }

    private fun write(state: Registry) {
        check(state.profiles.size <= 100) { "At most 100 local profiles are supported" }
        val temporary = Files.createTempFile(root, "profiles-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(Json.encodeToString(state).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try { Files.move(temporary, registry, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, registry, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private suspend fun <T> locked(action: () -> T): T = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root)) { "Profile root must not be a symbolic link" }
        val lock = root.resolve("profiles.lock")
        check(!Files.isSymbolicLink(lock)) { "Unsafe registry lock" }
        // JVM monitors also exclude same-process channels, whose file locks otherwise throw.
        synchronized(registryLock) {
            FileChannel.open(lock, CREATE, WRITE).use { channel -> channel.lock().use { action() } }
        }
    }

    private companion object { val registryLock = Any() }
}

package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object McpServerRepository {
    private val toolSelectionMutex = Mutex()
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val secretStore = secrets()
        updateSecret(secretStore, added, bearerToken)
        try {
            dao().insert(added.toEntity())
        } catch (failure: Throwable) {
            runCatching { secretStore.clear(added.id) }
            throw failure
        }
        return added
    }

    suspend fun update(server: McpServerSetting, bearerToken: String? = null) =
        toolSelectionMutex.withLock {
            updateUnlocked(server, bearerToken)
        }

    private suspend fun updateUnlocked(server: McpServerSetting, bearerToken: String? = null) {
        val previous = requireNotNull(serverById(server.id)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(server.id)
        val changesSecret = bearerToken != null || server.authorizationType == McpAuthorizationType.NONE
        val updated = if (bearerToken != null) {
            server.copy(lastProtocolVersion = null, toolsExpireAt = null)
        } else {
            server
        }
        if (changesSecret) updateSecret(secretStore, updated, bearerToken.orEmpty())
        try {
            require(dao().update(updated.toEntity()) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            if (changesSecret) runCatching {
                updateSecret(secretStore, previous, previousToken.orEmpty())
            }
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) = toolSelectionMutex.withLock {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        require(server.tools.any { it.name == toolName }) { "MCP 工具已不存在，请刷新列表" }
        val names = server.enabledToolNames.toMutableSet()
        if (enabled) names += toolName else names -= toolName
        updateUnlocked(server.copy(enabledToolNames = names))
    }

    suspend fun enableAllTools(serverId: String) = toolSelectionMutex.withLock {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        updateUnlocked(server.copy(enabledToolNames = server.tools.mapTo(mutableSetOf()) { it.name }))
    }

    /** Merge discovery with the latest switches, not the selection captured before network I/O. */
    suspend fun applyDiscovery(discovered: McpServerSetting): McpServerSetting = toolSelectionMutex.withLock {
        val current = requireNotNull(serverById(discovered.id)) { "MCP 服务器不存在" }
        val updated = current.withDiscoveredTools(discovered.tools).copy(
            lastRefreshedAt = discovered.lastRefreshedAt,
            lastProtocolVersion = discovered.lastProtocolVersion,
            toolsExpireAt = discovered.toolsExpireAt,
        )
        updateUnlocked(updated)
        updated
    }

    suspend fun delete(serverId: String) {
        val previous = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(serverId)
        secretStore.clear(serverId)
        try {
            require(dao().delete(serverId) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            runCatching { updateSecret(secretStore, previous, previousToken.orEmpty()) }
            throw failure
        }
    }

    fun bearerToken(serverId: String): String? = secrets().bearerToken(serverId)

    private fun updateSecret(
        secretStore: McpSecretStore,
        server: McpServerSetting,
        bearerToken: String,
    ) {
        if (server.authorizationType == McpAuthorizationType.BEARER) {
            secretStore.setBearerToken(server.id, bearerToken)
        } else {
            secretStore.clear(server.id)
        }
    }

    private fun dao() = EtaDatabase.get(context()).mcpServerDao()

    private fun secrets() = McpSecretStore(context())

    private fun context(): Context {
        check(::applicationContext.isInitialized) {
            "McpServerRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }
}

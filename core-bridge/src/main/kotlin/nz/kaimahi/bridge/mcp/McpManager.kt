package nz.kaimahi.bridge.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nz.kaimahi.bridge.storage.McpStore
import nz.kaimahi.bridge.tools.Tool
import nz.kaimahi.bridge.tools.ToolRegistry

/**
 * Top-level MCP coordinator. Owns the active client per configured
 * server, registers MCP-discovered tools into the shared `ToolRegistry`
 * the cloud + local agent paths both read from, and exposes refresh /
 * add / remove operations to the UI.
 *
 * Thread-safety: a single `Mutex` serialises mutations to `clients` and
 * the set of registered tool names. Concurrent `callTool` requests hit
 * independent clients and the transport, so they run in parallel.
 */
class McpManager(
    private val store: McpStore,
    private val registry: ToolRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val mutex = Mutex()

    /** Live clients keyed by server id. */
    private val clients = mutableMapOf<String, McpClient>()

    /** Names of tools currently registered as MCP-backed. Tracked so
     *  refresh can cleanly tear down old entries when a server's tool
     *  list changes. */
    private val registeredNames = mutableSetOf<String>()

    /** Load persisted servers and connect to enabled ones. Safe to call
     *  multiple times — re-syncs registry to the latest server set. */
    suspend fun bootstrap() {
        val servers = store.load()
        sync(servers)
    }

    /** Add or update a server config. Re-syncs immediately. */
    suspend fun upsert(config: McpServerConfig) {
        store.upsert(config)
        sync(store.load())
    }

    /** Remove a server by id and unregister its tools. */
    suspend fun remove(id: String) {
        store.remove(id)
        sync(store.load())
    }

    /** Re-poll the configured servers for fresh tool lists. Call this
     *  on app foreground to pick up server-side tool additions. */
    fun refreshInBackground() {
        scope.launch {
            sync(store.load())
        }
    }

    /** Returns the configured server list (for the Settings UI). */
    fun listServers(): List<McpServerConfig> = store.load()

    /** Sync the live client set to match the persisted server list. */
    private suspend fun sync(servers: List<McpServerConfig>) = mutex.withLock {
        // Unregister any tools whose servers are gone or now disabled.
        val activeIds = servers.filter { it.enabled }.map { it.id }.toSet()
        val toRemove = clients.keys - activeIds
        for (id in toRemove) clients.remove(id)
        // Drop tools whose namespace prefix no longer matches an active client.
        val staleNames = registeredNames.filter { name ->
            val prefix = name.substringBefore("__", missingDelimiterValue = "")
            prefix.isBlank() || prefix !in activeIds
        }
        for (n in staleNames) {
            registry.unregister(n)
            registeredNames.remove(n)
        }

        // Bring enabled servers online + (re-)discover their tools.
        for (cfg in servers.filter { it.enabled }) {
            try {
                val client = clients.getOrPut(cfg.id) { McpClient(cfg) }
                client.initialize()
                val tools = client.listTools()
                // Re-register: drop any previous tools from this server
                // first so a server removing a tool actually removes it.
                val ownedPrefix = "${cfg.id}__"
                val previouslyOwned = registeredNames.filter { it.startsWith(ownedPrefix) }
                for (n in previouslyOwned) {
                    registry.unregister(n)
                    registeredNames.remove(n)
                }
                for (t in tools) {
                    val tool: Tool = McpRemoteTool(client, t)
                    registry.register(tool)
                    registeredNames.add(tool.spec.name)
                }
            } catch (e: McpError) {
                // A flaky server shouldn't take down the whole sync —
                // skip and the next refresh will retry.
                continue
            }
        }
    }
}

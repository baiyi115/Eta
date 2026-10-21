package io.github.mangi.eta.agent.accessibility

/** Main-thread-only, bounded identities based on source-node equality, not optional app uniqueId. */
internal class PhoneUseNodeSources<N>(
    private val scope: String,
    private val copy: (N) -> N,
    private val maxDisplays: Int = 4,
) {
    private data class Snapshot<N>(val windowId: Int, val ids: Map<N, String>)
    private val displays = linkedMapOf<Int, Snapshot<N>>()
    private var sequence = 0L
    init { require(maxDisplays > 0) }

    fun identify(displayId: Int, windowId: Int, nodes: List<N>): List<String> {
        require(nodes.size <= 250)
        val previous = displays.remove(displayId)?.takeIf { it.windowId == windowId }?.ids.orEmpty()
        val current = linkedMapOf<N, String>()
        val result = nodes.map { node ->
            val id = previous[node] ?: current[node] ?: "$scope:${++sequence}"
            current[copy(node)] = id
            id
        }
        displays[displayId] = Snapshot(windowId, current)
        while (displays.size > maxDisplays) displays.remove(displays.keys.first())
        return result
    }

    fun retainWindows(live: Map<Int, Set<Int>>) {
        displays.entries.removeAll { (display, snapshot) -> snapshot.windowId !in live[display].orEmpty() }
    }
    fun clear() { displays.clear() }
}

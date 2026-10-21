/*
 * Adapted from MiAIPlus PageGuard.java (astoHu1), commit dc9107ab65fc5db2968e2f854a86735cff86a196.
 * SPDX-License-Identifier: Apache-2.0
 * Modifications: Kotlin integration, Eta frames and bounded partial-tree coverage. See assets/third_party/miaiplus.
 */
package io.github.mangi.eta.agent.phoneuse

import org.json.JSONObject
import org.json.JSONArray

/** Local target/cover checks adapted from MiAIPlus PageGuard; unknown subtrees are never actionable. */
internal object PhoneUseTargetGuard {
    data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val area get() = (right - left) * (bottom - top)
        fun contains(x: Double, y: Double) = x >= left && x < right && y >= top && y < bottom
        fun intersects(other: Bounds) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
    data class Node(val json: JSONObject, val bounds: Bounds) {
        val labeled get() = json.optString("text").isNotBlank() || json.optString("desc").isNotBlank()
        val semantic get() = labeled || json.optBoolean("clickable") || json.optBoolean("editable")
        val structure get() = listOf("class", "package", "view_id", "source_id", "window_id", "bounds", "clickable",
            "long_clickable", "scrollable", "editable", "focused", "password", "enabled", "checked", "selected")
            .let { keys -> JSONArray(keys.map { json.opt(it) ?: JSONObject.NULL }).toString() }
        val content get() = JSONArray(listOf("text", "desc", "hint").map { json.optString(it) }).toString()
    }
    private val numbers = Regex("""-?\d+""")
    fun bounds(value: String): Bounds? {
        val parts = numbers.findAll(value).mapNotNull { it.value.toDoubleOrNull() }.toList()
        return if (parts.size == 4 && parts[2] > parts[0] && parts[3] > parts[1]) Bounds(parts[0], parts[1], parts[2], parts[3]) else null
    }
    fun nodes(frame: PhoneUseFrame): List<Node> {
        val data = frame.content.optJSONArray("ui_nodes") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val node = data.optJSONObject(i) ?: return@mapNotNull null
            bounds(node.optString("bounds"))?.let { Node(node, it) }
        }
    }
    fun target(frame: PhoneUseFrame, action: PhoneUseAction): Node? {
        val all = nodes(frame)
        if (action.name == "type") return all.filter { it.json.optBoolean("focused") && it.json.optBoolean("editable") }.singleOrNull()
        val point = action.inScreenPixels(frame).element ?: return null
        return all.filter { it.bounds.contains(point.first, point.second) && it.semantic }.minByOrNull { it.bounds.area }
    }
    /** After a write, content is expected to change; identity and covering structure must not. */
    fun sameInputFieldAfterEdit(before: PhoneUseFrame, current: PhoneUseFrame): Boolean {
        if (before.displayId != current.displayId || before.width != current.width || before.height != current.height ||
            before.foreground != current.foreground || before.image.width != current.image.width ||
            before.image.height != current.image.height || before.content.optInt("window_id", -1) < 0 ||
            before.content.optInt("window_id", -1) != current.content.optInt("window_id", -1)) return false
        val action = PhoneUseAction("type")
        val old = target(before, action) ?: return false
        val now = target(current, action) ?: return false
        if (old.json.optString("source_id").isBlank() || old.structure != now.structure ||
            now.json.optBoolean("password") || !now.json.optBoolean("enabled")) return false
        if (!known(before, old.bounds) || !known(current, now.bounds)) return false
        val x = (old.bounds.left + old.bounds.right) / 2
        val y = (old.bounds.top + old.bounds.bottom) / 2
        return nodes(before).filter { it.bounds.contains(x, y) }.map { it.structure } ==
            nodes(current).filter { it.bounds.contains(x, y) }.map { it.structure }
    }

    private fun known(frame: PhoneUseFrame, region: Bounds): Boolean {
        if (!frame.content.optBoolean("ui_tree_truncated")) return true
        val unknown = frame.content.optJSONArray("unobserved_regions") ?: return false
        if (unknown.length() == 0) return false
        return (0 until unknown.length()).all { i ->
            val bounds = bounds(unknown.optString(i)) ?: return@all false
            !bounds.intersects(region)
        }
    }
    fun sameTarget(before: PhoneUseFrame, current: PhoneUseFrame, action: PhoneUseAction): Boolean {
        if (before.displayId != current.displayId || before.width != current.width || before.height != current.height ||
            before.foreground != current.foreground || before.image.width != current.image.width || before.image.height != current.image.height) return false
        if (action.name in setOf("launch", "wait", "home", "back")) return true
        // Compatibility for non-Android fixtures/backends that supply an opaque signature instead of semantic nodes.
        if (!before.content.has("ui_nodes") || !current.content.has("ui_nodes")) {
            return before.treeSignature == current.treeSignature && before.visualAnchor(action) == current.visualAnchor(action)
        }
        if (before.content.optInt("window_id", -1) != current.content.optInt("window_id", -1)) return false
        val pixels = action.inScreenPixels(before)
        val oldNodes = nodes(before)
        val newNodes = nodes(current)
        if (action.name == "swipe") {
            val start = pixels.start ?: return false
            val end = pixels.end ?: return false
            val corridor = Bounds(minOf(start.first, end.first) - 1, minOf(start.second, end.second) - 1,
                maxOf(start.first, end.first) + 1, maxOf(start.second, end.second) + 1)
            return known(before, corridor) && known(current, corridor) && PhoneUseVisualGuard.stable(before, current, corridor)
        }
        val oldTarget = target(before, action)
        val newTarget = target(current, action)
        if (action.name == "type" && (oldTarget == null || newTarget == null)) return false
        if (oldTarget?.structure != newTarget?.structure || oldTarget?.content != newTarget?.content) return false
        if (oldTarget?.json?.optBoolean("password") == true || oldTarget?.json?.optBoolean("enabled", true) == false) return false
        val point = pixels.element ?: oldTarget?.bounds?.let { (it.left + it.right) / 2 to (it.top + it.bottom) / 2 } ?: return false
        val region = if (action.name == "type") checkNotNull(oldTarget).bounds
            else Bounds(point.first - 1, point.second - 1, point.first + 1, point.second + 1)
        if (!known(before, region) || !known(current, region)) return false
        val oldCover = oldNodes.filter { it.bounds.contains(point.first, point.second) }
        val newCover = newNodes.filter { it.bounds.contains(point.first, point.second) }
        if (oldCover.map { it.structure } != newCover.map { it.structure }) return false
        val sameCover = oldCover.map { it.content } == newCover.map { it.content }
        if (sameCover && oldTarget?.labeled == true && action.name == "type") return true
        val visualRegion = oldTarget?.bounds?.takeIf { it.area <= before.width.toDouble() * before.height / 2 } ?: region
        return PhoneUseVisualGuard.stable(before, current, visualRegion)
    }
}

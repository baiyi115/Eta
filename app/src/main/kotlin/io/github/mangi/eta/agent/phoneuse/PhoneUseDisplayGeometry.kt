package io.github.mangi.eta.agent.phoneuse

import org.json.JSONObject

/** Native display geometry: image compression must not change the screen on which an app lays itself out. */
internal data class PhoneUseDisplayGeometry(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        fun fromHelper(metrics: JSONObject): PhoneUseDisplayGeometry {
            val result = PhoneUseDisplayGeometry(
                metrics.optInt("width"), metrics.optInt("height"), metrics.optInt("densityDpi"),
            )
            if (result.width !in 320..4096 || result.height !in 320..4096 || result.densityDpi !in 80..1000) {
                throw PhoneUseFailure("DISPLAY_GEOMETRY_UNAVAILABLE",
                    "主屏实际尺寸或显示密度无效或超出虚拟屏支持范围：${result.width}×${result.height}@${result.densityDpi}")
            }
            return result
        }
    }
}

package com.jev.probe.jev

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches the model list from an OpenAI-compatible `/models` endpoint and turns
 * it into something a picker can show.
 *
 * Two response shapes are in the wild and both are handled:
 *  - `{"object":"list","data":[{"id":"mimo-v2.6-flash"}, ...]}`  (OpenAI style)
 *  - `{"data":[{"id":"...","name":"...","context_length":N}]}`    (richer gateways)
 * A bare top-level array is accepted too. Anything else raises [ApiException]
 * via [HttpJson], so the caller shows the server's real reason.
 */
object ModelCatalog {

    /** One selectable model. [label] is what the picker shows; [id] is what is saved. */
    data class Entry(val id: String, val label: String)

    /**
     * GET `<baseUrl>/models`. [baseUrl] is the same value the route uses (up to
     * and including `/v1`). Sorted by id so a long list stays scannable.
     */
    fun fetch(baseUrl: String, key: String, route: String): List<Entry> {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "请先填 Base URL" }
        val url = "$base/models"
        val body = HttpJson.get(url, key, route, HttpJson.headersFor(url))
        val arr = extractArray(body)
        val out = LinkedHashMap<String, Entry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id").trim().ifBlank { item.optString("name").trim() }
            if (id.isBlank()) continue
            val name = item.optString("name").trim()
            val ctx = item.optLong("context_length", 0L)
            val label = buildString {
                append(if (name.isNotBlank() && name != id) "$id · $name" else id)
                if (ctx > 0) append("  (").append(ctx / 1000).append("k)")
            }
            out[id] = Entry(id, label)
        }
        return out.values.sortedBy { it.id.lowercase() }
    }

    /** The `data` array, a bare array, or the first array-valued field we find. */
    private fun extractArray(body: JSONObject): JSONArray {
        body.optJSONArray("data")?.let { return it }
        val keys = body.keys()
        while (keys.hasNext()) {
            body.optJSONArray(keys.next())?.let { return it }
        }
        return JSONArray()
    }
}

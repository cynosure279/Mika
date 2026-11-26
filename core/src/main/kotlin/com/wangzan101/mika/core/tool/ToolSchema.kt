package com.wangzan101.mika.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal data class NormalizedObjectSchema(
    val props: Map<String, JsonObject>,
    val required: List<String>,
)

/**
 * Normalizes external tool JSON Schemas (from MCP servers or plugins) into a clean object schema
 * compatible with OpenAI/DeepSeek/Anthropic/Gemini validators:
 * - resolves local `$ref` pointers with cycle detection and depth capping,
 * - flattens `allOf` / `anyOf` / `oneOf` combinators,
 * - collapses nullable `type` arrays and `const` values,
 * - ensures array schemas declare `items`,
 * - strips unsupported JSON Schema meta/constraint keywords,
 * - guarantees every key listed in `required` is present in `props`.
 */
internal object ToolSchema {
    private const val MAX_DEPTH = 8

    private val STRIPPED_KEYS = setOf(
        "\$schema", "\$id", "\$defs", "definitions", "\$ref", "\$comment",
        "\$vocabulary", "\$anchor", "\$dynamicRef", "\$dynamicAnchor",
        "not", "if", "then", "else",
        "dependentSchemas", "dependentRequired", "dependencies",
        "patternProperties", "unevaluatedProperties", "unevaluatedItems", "propertyNames",
        "examples", "readOnly", "writeOnly", "deprecated",
        "contentEncoding", "contentMediaType",
    )

    fun normalizeObject(schema: JsonObject): NormalizedObjectSchema {
        val normalized = normalizeNode(schema, root = schema, seenRefs = emptySet(), depth = 0, isRoot = true)
        val rawProps = (normalized["properties"] as? JsonObject).orEmpty()
        val props = linkedMapOf<String, JsonObject>()
        rawProps.forEach { (key, value) ->
            props[key] = when (value) {
                is JsonObject -> if (value.isEmpty()) JsonObject(emptyMap()) else value
                else -> JsonObject(emptyMap())
            }
        }
        val required = stringArray(normalized["required"]).distinct()
        // Ensure required keys absent from properties remain callable (e.g. ownReason or omitted properties).
        required.forEach { key ->
            if (key !in props) props[key] = JsonObject(emptyMap())
        }
        return NormalizedObjectSchema(props = props, required = required)
    }

    private fun normalizeNode(
        raw: JsonObject,
        root: JsonObject,
        seenRefs: Set<String>,
        depth: Int,
        isRoot: Boolean = false,
    ): JsonObject {
        if (raw.isEmpty() && !isRoot) return JsonObject(emptyMap())

        val ownDesc = stringProp(raw, "description")

        val ref = stringProp(raw, "\$ref")
        if (ref != null) {
            val resolved = if (ref in seenRefs) {
                refFallback(ownDesc)
            } else {
                val target = resolvePointer(root, ref)
                when {
                    target == null -> refFallback(ownDesc)
                    depth >= MAX_DEPTH -> cappedNode(target, ownDesc)
                    else -> normalizeNode(target, root, seenRefs + ref, depth + 1, isRoot = isRoot)
                }
            }
            val withoutRef = JsonObject(raw.filterKeys { it != "\$ref" })
            if (withoutRef.isEmpty()) return resolved
            val siblings = if (depth >= MAX_DEPTH) {
                JsonObject(buildMap {
                    ownDesc?.takeIf { it.isNotBlank() }?.let { put("description", JsonPrimitive(it)) }
                    raw["default"]?.let { put("default", it) }
                })
            } else {
                normalizeNode(withoutRef, root, seenRefs + ref, depth + 1, isRoot = isRoot)
            }
            return finalizeObjectHygiene(combineParentAndBase(parent = siblings, base = resolved), isRoot)
        }

        if (depth >= MAX_DEPTH && !isRoot) {
            return cappedNode(raw, ownDesc)
        }

        val working = raw

        val allOf = working["allOf"] as? JsonArray
        val anyOf = working["anyOf"] as? JsonArray
        val oneOf = working["oneOf"] as? JsonArray

        var base: JsonObject? = null
        if (allOf != null) {
            val branches = allOf.mapNotNull { it as? JsonObject }
                .map { normalizeNode(it, root, seenRefs, depth + 1) }
                .filterNot(::isNullOnly)
            if (branches.isNotEmpty()) base = mergeAllOf(branches)
        }

        for (unionArray in listOfNotNull(anyOf, oneOf)) {
            val branches = unionArray.mapNotNull { it as? JsonObject }
                .map { normalizeNode(it, root, seenRefs, depth + 1) }
                .filterNot(::isNullOnly)
            if (branches.isNotEmpty()) {
                val unionMerged = mergeUnion(branches)
                base = if (base != null) mergeAllOf(listOf(base, unionMerged)) else unionMerged
            }
        }

        val stripped = stripMetaAndTransform(working, root, seenRefs, depth)
        val merged = if (base != null) combineParentAndBase(stripped, base) else stripped

        return finalizeObjectHygiene(merged, isRoot)
    }

    private fun stripMetaAndTransform(
        raw: JsonObject,
        root: JsonObject,
        seenRefs: Set<String>,
        depth: Int,
    ): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        val constVal = raw["const"]

        raw.forEach { (k, v) ->
            when {
                k in STRIPPED_KEYS || k == "allOf" || k == "anyOf" || k == "oneOf" || k == "const" -> Unit
                k == "additionalProperties" -> {
                    // Preserve boolean additionalProperties; drop schema-valued additionalProperties to avoid unnormalized nested $ref.
                    if (v is JsonPrimitive && v.booleanOrNull != null) out[k] = v
                }
                k == "type" -> collapseType(v)?.let { out[k] = JsonPrimitive(it) }
                k == "properties" && v is JsonObject -> {
                    val normalizedProps = linkedMapOf<String, JsonElement>()
                    v.forEach { (propName, propSchema) ->
                        normalizedProps[propName] = when (propSchema) {
                            is JsonObject -> {
                                if (propSchema.isEmpty()) JsonObject(emptyMap())
                                else normalizeNode(propSchema, root, seenRefs, depth + 1)
                            }
                            else -> JsonObject(emptyMap())
                        }
                    }
                    out[k] = JsonObject(normalizedProps)
                }
                k == "items" -> {
                    out[k] = normalizeItems(v, root, seenRefs, depth)
                }
                k == "required" && v is JsonArray -> {
                    val req = v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() }?.content }.distinct()
                    if (req.isNotEmpty()) out[k] = JsonArray(req.map(::JsonPrimitive))
                }
                else -> out[k] = v
            }
        }

        if (constVal != null && constVal !is JsonNull) {
            if ("enum" !in out) out["enum"] = JsonArray(listOf(constVal))
            if ("type" !in out) inferPrimitiveType(constVal)?.let { out["type"] = JsonPrimitive(it) }
        }

        if ("type" !in out) {
            when {
                "properties" in out -> out["type"] = JsonPrimitive("object")
                "items" in out -> out["type"] = JsonPrimitive("array")
            }
        }

        if ((out["type"] as? JsonPrimitive)?.contentOrNull == "array" && out["items"] !is JsonObject) {
            out["items"] = JsonObject(mapOf("type" to JsonPrimitive("string")))
        }

        return JsonObject(out)
    }

    private fun normalizeItems(
        itemsEl: JsonElement,
        root: JsonObject,
        seenRefs: Set<String>,
        depth: Int,
    ): JsonObject = when (itemsEl) {
        is JsonObject -> {
            val normalized = if (itemsEl.isEmpty()) JsonObject(mapOf("type" to JsonPrimitive("string")))
            else normalizeNode(itemsEl, root, seenRefs, depth + 1)
            if (normalized.isEmpty()) JsonObject(mapOf("type" to JsonPrimitive("string"))) else normalized
        }
        is JsonArray -> {
            val first = itemsEl.firstOrNull() as? JsonObject
            if (first == null) JsonObject(mapOf("type" to JsonPrimitive("string")))
            else {
                val normalized = normalizeNode(first, root, seenRefs, depth + 1)
                if (normalized.isEmpty()) JsonObject(mapOf("type" to JsonPrimitive("string"))) else normalized
            }
        }
        else -> JsonObject(mapOf("type" to JsonPrimitive("string")))
    }

    private fun finalizeObjectHygiene(obj: JsonObject, isRoot: Boolean): JsonObject {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        if (type != null && type != "object") {
            if ("properties" in obj || "required" in obj) {
                return JsonObject(obj.filterKeys { it != "properties" && it != "required" })
            }
            return obj
        }
        if (!isRoot && type == "object") {
            val props = obj["properties"] as? JsonObject
            val req = stringArray(obj["required"])
            if (props != null && props.isEmpty() && req.isEmpty()) {
                return JsonObject(obj.filterKeys { it != "properties" && it != "required" })
            }
            if (props != null && req.isNotEmpty()) {
                val filteredReq = req.filter { it in props }
                if (filteredReq.size != req.size) {
                    val next = if (filteredReq.isEmpty()) obj.filterKeys { it != "required" }
                    else obj + ("required" to JsonArray(filteredReq.map(::JsonPrimitive)))
                    return if (props.isEmpty()) JsonObject(next.filterKeys { it != "properties" }) else JsonObject(next)
                }
            }
        }
        return obj
    }

    private fun combineParentAndBase(
        parent: JsonObject,
        base: JsonObject,
    ): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        base.forEach { (k, v) -> out[k] = v }
        parent.forEach { (k, v) ->
            when {
                k == "properties" && v is JsonObject && out[k] is JsonObject -> {
                    val baseProps = (out[k] as JsonObject).toMutableMap()
                    v.forEach { (pk, pv) -> baseProps[pk] = pv }
                    out[k] = JsonObject(baseProps)
                }
                k == "required" && v is JsonArray -> {
                    val parentReq = stringArray(v)
                    val baseReq = stringArray(out[k])
                    val mergedReq = (baseReq + parentReq).distinct()
                    if (mergedReq.isNotEmpty()) out[k] = JsonArray(mergedReq.map(::JsonPrimitive))
                }
                k == "description" -> {
                    val pDesc = (v as? JsonPrimitive)?.contentOrNull
                    if (!pDesc.isNullOrBlank()) out[k] = v
                }
                else -> out[k] = v
            }
        }
        return JsonObject(out)
    }

    private fun mergeAllOf(branches: List<JsonObject>): JsonObject {
        if (branches.size == 1) return branches.first()
        val out = linkedMapOf<String, JsonElement>()
        val props = linkedMapOf<String, JsonElement>()
        val req = linkedSetOf<String>()
        branches.forEach { branch ->
            branch.forEach { (k, v) ->
                when (k) {
                    "properties" -> if (v is JsonObject) v.forEach { (pk, pv) -> props[pk] = pv }
                    "required" -> if (v is JsonArray) req.addAll(stringArray(v))
                    else -> if (k !in out) out[k] = v else if (k == "type" && (out[k] as? JsonPrimitive)?.contentOrNull != "object") out[k] = v
                }
            }
        }
        if (props.isNotEmpty()) {
            out["properties"] = JsonObject(props)
            out["type"] = JsonPrimitive("object")
        }
        if (req.isNotEmpty()) out["required"] = JsonArray(req.map(::JsonPrimitive))
        return JsonObject(out)
    }

    private fun mergeUnion(branches: List<JsonObject>): JsonObject {
        if (branches.size == 1) return branches.first()

        // Collapse multiple scalar enum/const options into one enum schema when type-compatible.
        val allEnumLike = branches.all { b -> "properties" !in b && (b["enum"] is JsonArray || "const" in b) }
        if (allEnumLike) {
            val values = branches.flatMap { b -> (b["enum"] as? JsonArray)?.toList().orEmpty() }.distinct()
            val types = branches.mapNotNull { (it["type"] as? JsonPrimitive)?.contentOrNull }.distinct()
            val valueTypes = values.mapNotNull(::inferPrimitiveType).distinct()
            if (types.size <= 1 && valueTypes.size <= 1) {
                val out = linkedMapOf<String, JsonElement>()
                out["type"] = JsonPrimitive(types.firstOrNull() ?: valueTypes.firstOrNull() ?: "string")
                if (values.isNotEmpty()) out["enum"] = JsonArray(values)
                branches.firstNotNullOfOrNull { stringProp(it, "description") }?.let { out["description"] = JsonPrimitive(it) }
                return JsonObject(out)
            }
        }

        // If all branches are object-like, merge their properties (recursively merging colliding property schemas)
        // and intersect required keys.
        val allObjects = branches.all { b ->
            (b["type"] as? JsonPrimitive)?.contentOrNull == "object" || "properties" in b || "required" in b
        }
        if (allObjects) {
            val props = linkedMapOf<String, JsonElement>()
            branches.forEach { b ->
                (b["properties"] as? JsonObject)?.forEach { (pk, pv) ->
                    val prev = props[pk]
                    props[pk] = when {
                        prev == null -> pv
                        prev is JsonObject && pv is JsonObject && prev != pv -> mergeUnion(listOf(prev, pv))
                        else -> prev
                    }
                }
            }
            val reqLists = branches.map { stringArray(it["required"]).toSet() }
            val commonReq = reqLists.reduceOrNull { acc, s -> acc.intersect(s) }.orEmpty()
            val out = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("object"))
            if (props.isNotEmpty()) out["properties"] = JsonObject(props)
            if (commonReq.isNotEmpty()) out["required"] = JsonArray(commonReq.map(::JsonPrimitive))
            branches.firstNotNullOfOrNull { stringProp(it, "description") }?.let { out["description"] = JsonPrimitive(it) }
            return JsonObject(out)
        }

        // Mixed union: pick first non-null branch and preserve type alternatives in description.
        val primary = branches.first()
        val types = branches.mapNotNull { (it["type"] as? JsonPrimitive)?.contentOrNull }.distinct()
        if (types.size <= 1) return primary
        val hint = "Accepts: ${types.joinToString(" | ")}"
        val existingDesc = stringProp(primary, "description")
        val mergedDesc = if (existingDesc.isNullOrBlank()) hint else "$existingDesc ($hint)"
        return JsonObject(primary + ("description" to JsonPrimitive(mergedDesc)))
    }

    private fun cappedNode(obj: JsonObject, siblingDesc: String?): JsonObject {
        val type = collapseType(obj["type"])
            ?: when {
                "properties" in obj -> "object"
                "items" in obj -> "array"
                else -> "string"
            }
        val out = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
        if (type == "array") {
            out["items"] = JsonObject(mapOf("type" to JsonPrimitive("string")))
        }
        val desc = siblingDesc?.takeIf { it.isNotBlank() } ?: stringProp(obj, "description")?.takeIf { it.isNotBlank() }
        if (desc != null) out["description"] = JsonPrimitive(desc)
        return JsonObject(out)
    }

    private fun refFallback(siblingDesc: String?): JsonObject =
        JsonObject(
            buildMap {
                put("type", JsonPrimitive("string"))
                if (!siblingDesc.isNullOrBlank()) put("description", JsonPrimitive(siblingDesc))
            },
        )

    private fun resolvePointer(root: JsonObject, ref: String): JsonObject? {
        if (!ref.startsWith("#/")) return null
        val parts = ref.substring(2).split('/').map { percentDecode(it).replace("~1", "/").replace("~0", "~") }
        var cur: JsonElement = root
        for (segment in parts) {
            cur = when (cur) {
                is JsonObject -> cur[segment] ?: return null
                is JsonArray -> {
                    val idx = segment.toIntOrNull() ?: return null
                    cur.getOrNull(idx) ?: return null
                }
                else -> return null
            }
        }
        return cur as? JsonObject
    }

    private fun percentDecode(segment: String): String {
        if ('%' !in segment) return segment
        val bytes = java.io.ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val ch = segment[i]
            if (ch == '%' && i + 2 < segment.length) {
                val hex = segment.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            // 到下一个 % 为止整段按 UTF-8 写入:逐字符编码会把代理对拆坏。
            // write(ByteArray, Int, Int) 而不是 writeBytes:后者到 API 33 才有,minSdk 29 上会 NoSuchMethodError
            val end = segment.indexOf('%', i + 1).let { if (it < 0) segment.length else it }
            val chunk = segment.substring(i, end).toByteArray(Charsets.UTF_8)
            bytes.write(chunk, 0, chunk.size)
            i = end
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun collapseType(typeEl: JsonElement?): String? = when (typeEl) {
        is JsonPrimitive -> typeEl.contentOrNull?.takeIf { it != "null" && it.isNotBlank() }
        is JsonArray -> typeEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it != "null" && it.isNotBlank() }
        else -> null
    }

    private fun isNullOnly(obj: JsonObject): Boolean {
        val t = obj["type"]
        return when {
            t is JsonPrimitive && t.contentOrNull == "null" -> true
            t is JsonArray && t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.all { it == "null" } -> true
            else -> false
        }
    }

    private fun inferPrimitiveType(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            value.longOrNull != null -> "integer"
            value.doubleOrNull != null -> "number"
            else -> null
        }
        is JsonObject -> "object"
        is JsonArray -> "array"
    }

    private fun stringProp(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun stringArray(el: JsonElement?): List<String> =
        (el as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() }?.content }
}

package com.wangzan101.mika.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSchemaTest {
    private fun typeOf(schema: String, prop: String) =
        ToolSchema.normalizeObject(Json.parseToJsonElement(schema).jsonObject).props.getValue(prop)["type"]?.jsonPrimitive?.content

    @Test fun refWithPercentEncodedNonAsciiNameResolves() {
        val schema = """{"type":"object","${'$'}defs":{"名字":{"type":"integer"}},
            "properties":{"n":{"${'$'}ref":"#/${'$'}defs/%E5%90%8D%E5%AD%97"}}}"""
        assertEquals("integer", typeOf(schema, "n"))
    }

    @Test fun refMixingRawNonAsciiAndEscapesResolves() {
        val schema = """{"type":"object","${'$'}defs":{"😀 a":{"type":"boolean"}},
            "properties":{"n":{"${'$'}ref":"#/${'$'}defs/😀%20a"}}}"""
        assertEquals("boolean", typeOf(schema, "n"))
    }
}

package io.github.jackwener.dsh.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityDescriptorsTest {
    @Test
    fun catalogueExactlyMatchesTheMikaBridgeDispatchNames() {
        val tools = DshCapabilityDescriptors.all
        assertEquals(bridgeDispatchNames.size, tools.size)
        assertEquals(bridgeDispatchNames, tools.map { it.name }.toSet())
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
        assertEquals(DshCapabilityGroup.entries.toSet(), tools.map { it.group }.toSet())

        val validGroups = DshCapabilityGroup.entries.map { it.wire }.toSet()
        val validRisks = DshCapabilityRisk.entries.map { it.wire }.toSet()
        tools.forEach { tool ->
            assertTrue("invalid name ${tool.name}", Regex("^[A-Za-z0-9_-]{1,64}$").matches(tool.name))
            assertTrue(tool.description.isNotBlank())
            assertTrue(tool.group.wire in validGroups)
            assertTrue(tool.risk.wire in validRisks)
            assertTrue(tool.timeoutMs > 0)
            assertEquals("object", (tool.inputSchema["type"] as JsonPrimitive).content)
            assertTrue(tool.inputSchema["properties"] is JsonObject)
            assertTrue(tool.inputSchema["required"] is JsonArray)
        }
    }

    @Test
    fun allSchemasAreClosedObjectsWithDeclaredRequiredFields() {
        DshCapabilityDescriptors.all.forEach { tool ->
            validateObjectSchema(tool.inputSchema, tool.name)
        }
    }

    @Test
    fun schemasUseTheBridgeArgumentNamesAndTypes() {
        val byName = DshCapabilityDescriptors.all.associateBy { it.name }

        assertEquals(setOf("ocr"), propertyNames(byName.getValue("screen_read")))
        assertEquals(setOf("node", "text", "x", "y"), propertyNames(byName.getValue("screen_tap")))
        assertEquals("integer", propertyType(byName.getValue("screen_tap"), "x"))

        assertEquals(setOf("query"), propertyNames(byName.getValue("app_list")))
        assertEquals(setOf("package", "cold"), propertyNames(byName.getValue("app_launch")))
        assertEquals(listOf("package"), requiredNames(byName.getValue("app_launch")))
        assertFalse("app_launch has no activity selector", "activity" in propertyNames(byName.getValue("app_launch")))

        assertEquals("integer", propertyType(byName.getValue("browser_click"), "id"))
        assertEquals("integer", propertyType(byName.getValue("browser_type"), "id"))
        assertEquals(
            setOf("down", "up", "top", "bottom"),
            enumValues(byName.getValue("browser_scroll"), "direction"),
        )
        assertEquals(setOf("direction"), propertyNames(byName.getValue("browser_scroll")))

        assertEquals(setOf("percent", "auto"), propertyNames(byName.getValue("brightness_set")))
        assertEquals(
            setOf("from", "to", "fromMs", "toMs"),
            propertyNames(byName.getValue("calendar_read")),
        )
        assertEquals("integer", propertyType(byName.getValue("calendar_read"), "fromMs"))
        assertTrue(
            "calendar_add must support either ISO or epoch forms",
            setOf("start", "startMs", "end", "endMs").all {
                it in propertyNames(byName.getValue("calendar_add"))
            },
        )

        assertEquals(emptySet<String>(), propertyNames(byName.getValue("device_info")))
        assertEquals(setOf("reason"), propertyNames(byName.getValue("hand_to_user")))
        assertEquals(listOf("reason"), requiredNames(byName.getValue("hand_to_user")))

        assertEquals(DshCapabilityGroup.SHELL, byName.getValue("shell_run").group)
        assertEquals(DshCapabilityRisk.DANGEROUS, byName.getValue("shell_run").risk)
        assertEquals(setOf("command"), propertyNames(byName.getValue("shell_run")))
        assertEquals(listOf("command"), requiredNames(byName.getValue("shell_run")))

        assertEquals(listOf("namespace", "key"), requiredNames(byName.getValue("setting_get")))
        assertEquals(setOf("namespace", "key"), propertyNames(byName.getValue("setting_get")))
        assertEquals(
            listOf("namespace", "key", "value"),
            requiredNames(byName.getValue("setting_put")),
        )

        assertTrue(
            "unsupported bridge names must not be published",
            bridgeDispatchNames.intersect(
                setOf(
                    "shell_exec",
                    "phone_shell",
                    "device_status",
                    "wifi_toggle",
                    "bluetooth_toggle",
                    "mobile_data_toggle",
                    "location_get",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun riskAndUnlockMetadataMatchBridgePolicy() {
        val byName = DshCapabilityDescriptors.all.associateBy { it.name }

        assertEquals(DshCapabilityRisk.READ, byName.getValue("screen_read").risk)
        assertFalse(byName.getValue("screen_read").needsUnlock)
        assertEquals(DshCapabilityDescriptors.READ_TIMEOUT_MS, byName.getValue("screen_read").timeoutMs)

        assertEquals(DshCapabilityRisk.WRITE, byName.getValue("screen_tap").risk)
        assertTrue(byName.getValue("screen_tap").needsUnlock)
        assertEquals(DshCapabilityRisk.SENSITIVE, byName.getValue("notification_list").risk)
        assertEquals(DshCapabilityRisk.SENSITIVE, byName.getValue("clipboard_get").risk)
        assertEquals(DshCapabilityRisk.DANGEROUS, byName.getValue("file_delete").risk)
        assertEquals(DshCapabilityRisk.DANGEROUS, byName.getValue("sms_send").risk)
        assertEquals(DshCapabilityRisk.DANGEROUS, byName.getValue("shell_run").risk)
        assertFalse(byName.getValue("shell_run").needsUnlock)

        assertTrue(byName.getValue("app_launch").needsUnlock)
        assertTrue(byName.getValue("alarm_set").needsUnlock)
        assertEquals(
            DshCapabilityDescriptors.UNLOCK_TIMEOUT_MS,
            byName.getValue("alarm_set").timeoutMs,
        )
        assertFalse(byName.getValue("browser_read").needsUnlock)
        assertFalse(byName.getValue("file_read").needsUnlock)
    }

    @Test
    fun aliasesNormalizeOnlyBridgeSupportedMikaCalls() {
        assertEquals(23, DshCapabilityDescriptors.mikaAliases.size)
        assertEquals(
            mikaDispatchNames,
            DshCapabilityDescriptors.mikaAliases.values.toSet(),
        )
        assertTrue(
            "aliases refer to unknown capabilities",
            DshCapabilityDescriptors.mikaAliases.keys.all { it in bridgeDispatchNames },
        )

        val screenRead = DshCapabilityDescriptors.normalizeMikaCall(
            "screen_read",
            JsonObject(emptyMap()),
        )
        assertEquals("phone_list_elements", screenRead?.tool)

        val swipe = DshCapabilityDescriptors.normalizeMikaCall(
            "screen_swipe",
            jsonObject(
                "x1" to JsonPrimitive(10),
                "y1" to JsonPrimitive(20),
                "x2" to JsonPrimitive(30),
                "y2" to JsonPrimitive(40),
                "durationMs" to JsonPrimitive(500),
            ),
        )
        assertEquals("phone_swipe", swipe?.tool)
        assertEquals("10", swipe?.arguments?.get("startX")?.jsonText())
        assertEquals("40", swipe?.arguments?.get("endY")?.jsonText())
        assertNull(swipe?.arguments?.get("x1"))

        val launch = DshCapabilityDescriptors.normalizeMikaCall(
            "app_launch",
            jsonObject(
                "package" to JsonPrimitive("com.example"),
                "cold" to JsonPrimitive(true),
            ),
        )
        assertEquals("phone_launch_app", launch?.tool)
        assertEquals(true, (launch?.arguments?.get("cold") as JsonPrimitive).booleanOrNull)

        val browser = DshCapabilityDescriptors.normalizeMikaCall(
            "browser_open",
            jsonObject("url" to JsonPrimitive("https://example.com")),
        )
        assertEquals("web_navigate", browser?.tool)
        assertEquals("https://example.com", browser?.arguments?.get("url")?.jsonText())

        val shell = DshCapabilityDescriptors.normalizeMikaCall(
            "shell_run",
            jsonObject("command" to JsonPrimitive("pwd")),
        )
        assertEquals("phone_system_command", shell?.tool)
        assertEquals("pwd", shell?.arguments?.get("command")?.jsonText())

        val enter = DshCapabilityDescriptors.normalizeMikaCall(
            "screen_key",
            jsonObject("key" to JsonPrimitive("enter")),
        )
        assertEquals("phone_press_key", enter?.tool)
        assertEquals("KEYCODE_ENTER", enter?.arguments?.get("key")?.jsonText())

        assertNull(
            DshCapabilityDescriptors.normalizeMikaCall(
                "screen_read",
                jsonObject("ocr" to JsonPrimitive(true)),
            ),
        )
        assertNull(
            DshCapabilityDescriptors.normalizeMikaCall(
                "share_to_app",
                jsonObject("paths" to JsonArray(listOf(JsonPrimitive("/sdcard/a.txt")))),
            ),
        )
        assertNull(
            DshCapabilityDescriptors.normalizeMikaCall(
                "screen_type",
                jsonObject(
                    "text" to JsonPrimitive("more"),
                    "append" to JsonPrimitive(true),
                ),
            ),
        )
        assertNull(
            DshCapabilityDescriptors.normalizeMikaCall(
                "notification_list",
                JsonObject(emptyMap()),
            ),
        )
    }

    @Test
    fun capabilityListUsesTheBridgeJsonShape() {
        val payload = DshCapabilityDescriptors.capabilityListJson()
        val encoded = payload.toString()
        val reparsed = Json.parseToJsonElement(encoded) as JsonObject
        val tools = reparsed["tools"] as JsonArray

        assertEquals(DshCapabilityDescriptors.all.size, tools.size)
        tools.forEach { raw ->
            val descriptor = raw as JsonObject
            listOf(
                "name",
                "group",
                "description",
                "inputSchema",
                "risk",
                "needsUnlock",
                "timeoutMs",
            ).forEach { key ->
                assertNotNull("missing $key in $descriptor", descriptor[key])
            }
            assertTrue(descriptor["name"]?.jsonText()?.isNotBlank() == true)
            assertTrue(descriptor["description"]?.jsonText()?.isNotBlank() == true)
            assertEquals("object", (descriptor["inputSchema"] as JsonObject)["type"]?.jsonText())
            assertTrue(
                descriptor["risk"]?.jsonText() in
                    DshCapabilityRisk.entries.map { it.wire },
            )
            assertNotNull((descriptor["needsUnlock"] as JsonPrimitive).booleanOrNull)
            assertTrue(((descriptor["timeoutMs"] as JsonPrimitive).longOrNull ?: 0L) > 0L)
        }
    }

    private fun validateObjectSchema(schema: JsonObject, path: String) {
        assertEquals("$path.type", "object", schema["type"]?.jsonText())
        val properties = schema["properties"] as JsonObject
        val required = schema["required"] as JsonArray
        assertTrue("$path.required entries", required.all { it is JsonPrimitive && it.isString })
        val requiredNames = required.map { it.jsonText()!! }
        assertTrue("$path declares an undeclared required field", requiredNames.all(properties::containsKey))
        assertEquals(false, (schema["additionalProperties"] as JsonPrimitive).booleanOrNull)

        properties.forEach { (name, property) ->
            val child = property as JsonObject
            val type = child["type"]?.jsonText()
            assertTrue(
                "$path.$name type",
                type in setOf("object", "array", "string", "integer", "number", "boolean"),
            )
            assertTrue("$path.$name description", child["description"]?.jsonText()?.isNotBlank() == true)
            val minimum = (child["minimum"] as? JsonPrimitive)?.intOrNull
            val maximum = (child["maximum"] as? JsonPrimitive)?.intOrNull
            if (minimum != null && maximum != null) assertTrue("$path.$name bounds", minimum <= maximum)
            when (type) {
                "array" -> validatePropertySchema(child["items"] as JsonObject, "$path.$name[]")
                "object" -> if (child["additionalProperties"]?.jsonText() == "false") {
                    validateObjectSchema(child, "$path.$name")
                }
            }
        }
    }

    private fun validatePropertySchema(schema: JsonObject, path: String) {
        val type = schema["type"]?.jsonText()
        assertTrue(
            "$path type",
            type in setOf("object", "array", "string", "integer", "number", "boolean"),
        )
        if (type == "object" && schema["additionalProperties"]?.jsonText() == "false") {
            validateObjectSchema(schema, path)
        }
        if (type == "array") validatePropertySchema(schema["items"] as JsonObject, "$path[]")
    }

    private fun propertyNames(tool: DshCapabilityDescriptor): Set<String> =
        (tool.inputSchema["properties"] as JsonObject).keys

    private fun requiredNames(tool: DshCapabilityDescriptor): List<String> =
        (tool.inputSchema["required"] as JsonArray).map { it.jsonText()!! }

    private fun property(tool: DshCapabilityDescriptor, name: String): JsonObject =
        (tool.inputSchema["properties"] as JsonObject).getValue(name) as JsonObject

    private fun propertyType(tool: DshCapabilityDescriptor, name: String): String? =
        property(tool, name)["type"]?.jsonText()

    private fun enumValues(tool: DshCapabilityDescriptor, name: String): Set<String> =
        (property(tool, name)["enum"] as JsonArray).map { it.jsonText()!! }.toSet()

    private fun jsonObject(vararg entries: Pair<String, JsonElement>) = JsonObject(entries.toMap())

    private fun JsonElement?.jsonText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        val bridgeDispatchNames = setOf(
            "screen_read",
            "screen_capture",
            "screen_tap",
            "screen_type",
            "screen_swipe",
            "screen_scroll",
            "screen_key",
            "screen_wait",
            "hand_to_user",
            "app_list",
            "app_info",
            "app_launch",
            "app_open_link",
            "app_send_intent",
            "share_to_app",
            "file_search",
            "file_list",
            "file_read",
            "file_stat",
            "file_write",
            "file_mkdir",
            "file_move",
            "file_delete",
            "archive_create",
            "archive_extract",
            "browser_open",
            "browser_read",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_back",
            "browser_screenshot",
            "notification_list",
            "notification_dismiss",
            "notification_open",
            "notification_reply",
            "contacts_search",
            "sms_read",
            "call_log_read",
            "calendar_read",
            "calendar_add",
            "sms_send",
            "media_control",
            "clipboard_get",
            "clipboard_set",
            "device_info",
            "sound_mode",
            "volume_set",
            "dnd_set",
            "brightness_set",
            "flashlight",
            "alarm_set",
            "setting_get",
            "setting_put",
            "shell_run",
        )

        val mikaDispatchNames = setOf(
            "phone_list_elements",
            "phone_screenshot",
            "phone_tap",
            "phone_type_text",
            "phone_swipe",
            "phone_scroll",
            "phone_press_key",
            "phone_wait",
            "phone_list_apps",
            "phone_launch_app",
            "phone_open_link",
            "phone_share_text",
            "web_navigate",
            "web_read_page",
            "web_tap",
            "web_type_text",
            "web_scroll",
            "web_back",
            "web_screenshot",
            "read_file",
            "write_file",
            "search_files",
            "phone_system_command",
        )
    }
}

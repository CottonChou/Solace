package io.github.jackwener.dsh.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class DshCapabilityRisk(val wire: String) {
    READ("read"),
    WRITE("write"),
    SENSITIVE("sensitive"),
    DANGEROUS("dangerous"),
}

enum class DshCapabilityGroup(val wire: String) {
    SCREEN("screen"),
    APPS("apps"),
    BROWSER("browser"),
    FILES("files"),
    NOTIFICATIONS("notifications"),
    COMMS("comms"),
    CLIPBOARD("clipboard"),
    MEDIA("media"),
    SYSTEM("system"),
    SHELL("shell"),
}

/**
 * One entry in DSH's `capability/list` response.
 *
 * The catalogue is intentionally Android-free so its contract can be checked with JVM tests before
 * the host bridge is available.
 */
data class DshCapabilityDescriptor(
    val name: String,
    val group: DshCapabilityGroup,
    val description: String,
    val inputSchema: JsonObject,
    val risk: DshCapabilityRisk,
    val needsUnlock: Boolean,
    val timeoutMs: Long,
) {
    init {
        require(NAME.matches(name)) { "invalid capability name: $name" }
        require(description.isNotBlank()) { "$name has no description" }
        require(timeoutMs > 0) { "$name has a non-positive timeout" }
        require((inputSchema["type"] as? JsonPrimitive)?.contentOrNull == "object") {
            "$name schema must describe an object"
        }
        val properties = inputSchema["properties"] as? JsonObject
            ?: throw IllegalArgumentException("$name schema has no properties object")
        val required = inputSchema["required"] as? JsonArray
            ?: throw IllegalArgumentException("$name schema has no required array")
        require(required.all { it is JsonPrimitive && it.isString }) {
            "$name schema required entries must be strings"
        }
        require(required.all { (it as? JsonPrimitive)?.contentOrNull in properties }) {
            "$name schema requires a property that is not declared"
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("group", group.wire)
        put("description", description)
        put("inputSchema", inputSchema)
        put("risk", risk.wire)
        put("needsUnlock", needsUnlock)
        put("timeoutMs", timeoutMs)
    }

    private companion object {
        val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/** A normalized call into Solace's own tool dispatcher. */
data class SolaceCapabilityCall(
    val tool: String,
    val arguments: JsonObject,
)

/**
 * The device capability catalogue published by the Solace Android bridge.
 *
 * Every descriptor name is also a bridge dispatch target. The schemas describe only arguments the
 * bridge actually consumes. Solace aliases are limited to calls that can be translated without
 * silently dropping information.
 */
object DshCapabilityDescriptors {
    const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
    const val UNLOCK_TIMEOUT_MS = 15 * 60_000L
    const val READ_TIMEOUT_MS = 2 * 60_000L

    val all: List<DshCapabilityDescriptor> = listOf(
        // Screen
        descriptor(
            "screen_read", DshCapabilityGroup.SCREEN, DshCapabilityRisk.READ, false,
            "Read the managed Android screen as accessibility elements, adding OCR when requested.",
            timeoutMs = READ_TIMEOUT_MS,
        ) {
            put("ocr", bool("Also include local OCR text."))
        },
        descriptor(
            "screen_capture", DshCapabilityGroup.SCREEN, DshCapabilityRisk.READ, false,
            "Capture the managed Android screen as a PNG.",
            timeoutMs = READ_TIMEOUT_MS,
        ),
        descriptor(
            "screen_tap", DshCapabilityGroup.SCREEN, DshCapabilityRisk.WRITE, true,
            "Tap an accessibility element, visible text, or screen-pixel coordinates.",
        ) {
            put("node", string("Element id from screen_read."))
            put("text", string("Visible text or accessibility description to tap."))
            put("x", integer("X coordinate in screen pixels."))
            put("y", integer("Y coordinate in screen pixels."))
        },
        descriptor(
            "screen_type", DshCapabilityGroup.SCREEN, DshCapabilityRisk.WRITE, true,
            "Type into a managed-screen field, replacing its text unless append is true.",
            listOf("text"),
        ) {
            put("text", string("Text to enter."))
            put("node", string("Editable element id from screen_read."))
            put("append", bool("Append instead of replacing the field content."))
            put("submit", bool("Press Enter after typing."))
        },
        descriptor(
            "screen_swipe", DshCapabilityGroup.SCREEN, DshCapabilityRisk.WRITE, true,
            "Swipe or drag between two screen-pixel coordinates.",
            listOf("x1", "y1", "x2", "y2"),
        ) {
            put("x1", integer("Starting X coordinate in screen pixels."))
            put("y1", integer("Starting Y coordinate in screen pixels."))
            put("x2", integer("Ending X coordinate in screen pixels."))
            put("y2", integer("Ending Y coordinate in screen pixels."))
            put("durationMs", integer("Gesture duration in milliseconds.", 50, 5_000))
        },
        descriptor(
            "screen_scroll", DshCapabilityGroup.SCREEN, DshCapabilityRisk.WRITE, true,
            "Scroll the managed screen or one of its scrollable elements.",
            listOf("direction"),
        ) {
            put("direction", enum("Direction to scroll.", "down", "up", "left", "right"))
            put("node", string("Optional scrollable element id."))
        },
        descriptor(
            "screen_key", DshCapabilityGroup.SCREEN, DshCapabilityRisk.WRITE, true,
            "Press a navigation, editor, or system key.",
            listOf("key"),
        ) {
            put(
                "key",
                enum(
                    "Key to press.",
                    "enter", "search", "delete", "back", "home", "recents", "notifications",
                ),
            )
        },
        descriptor(
            "screen_wait", DshCapabilityGroup.SCREEN, DshCapabilityRisk.READ, true,
            "Wait until text appears or disappears, or until a package is in front.",
        ) {
            put("text", string("Visible text or accessibility description to wait for."))
            put("package", string("Package name that must be in front."))
            put("gone", bool("Wait for the text to disappear instead of appear."))
            put("timeoutMs", integer("Wait timeout in milliseconds.", 500, 30_000))
        },
        descriptor(
            "hand_to_user", DshCapabilityGroup.SCREEN, DshCapabilityRisk.READ, false,
            "Ask the user to handle a sensitive step on the phone.",
            listOf("reason"),
        ) {
            put("reason", string("What the user should do."))
        },

        // Apps
        descriptor(
            "app_list", DshCapabilityGroup.APPS, DshCapabilityRisk.READ, false,
            "List installed user-launchable apps, optionally filtered by label or package.",
            timeoutMs = READ_TIMEOUT_MS,
        ) {
            put("query", string("Substring of an app label or package name."))
        },
        descriptor(
            "app_info", DshCapabilityGroup.APPS, DshCapabilityRisk.READ, false,
            "Read package metadata and exported activities for an installed app.",
            listOf("package"),
            timeoutMs = READ_TIMEOUT_MS,
        ) {
            put("package", string("Android package name."))
        },
        descriptor(
            "app_launch", DshCapabilityGroup.APPS, DshCapabilityRisk.WRITE, true,
            "Launch an installed app, optionally restarting it from a cold state.",
            listOf("package"),
        ) {
            put("package", string("Android package name."))
            put("cold", bool("Restart the app before opening it."))
        },
        descriptor(
            "app_open_link", DshCapabilityGroup.APPS, DshCapabilityRisk.WRITE, true,
            "Open a web, app, telephone, mail, or other Android URI.",
            listOf("uri"),
        ) {
            put("uri", string("URI or deep link to open."))
            put("package", string("Optional package restriction."))
        },
        descriptor(
            "app_send_intent", DshCapabilityGroup.APPS, DshCapabilityRisk.WRITE, true,
            "Start an Android intent with action, data, MIME type, package, and primitive extras.",
            listOf("action"),
        ) {
            put("action", string("Android intent action."))
            put("data", string("Optional data URI."))
            put("type", string("Optional MIME type."))
            put("package", string("Optional target package."))
            put("extras", objectProperty("Optional string, boolean, integer, long, or number intent extras."))
        },
        descriptor(
            "share_to_app", DshCapabilityGroup.APPS, DshCapabilityRisk.WRITE, true,
            "Share files and/or text to another app or the system chooser.",
        ) {
            put("paths", array("Files to share.", stringSchema("A shared-storage file path.")))
            put("text", string("Optional text to share."))
            put("package", string("Optional target package."))
        },

        // Browser
        descriptor(
            "browser_open", DshCapabilityGroup.BROWSER, DshCapabilityRisk.READ, false,
            "Open an HTTP or HTTPS URL in the in-app browser.",
            listOf("url"),
        ) {
            put("url", string("HTTP or HTTPS URL."))
        },
        descriptor(
            "browser_read", DshCapabilityGroup.BROWSER, DshCapabilityRisk.READ, false,
            "Read the current page text and interactive elements.",
        ) {
            put("maxChars", integer("Maximum characters to return.", 500, 40_000))
        },
        descriptor(
            "browser_click", DshCapabilityGroup.BROWSER, DshCapabilityRisk.WRITE, false,
            "Click an element returned by browser_read.",
            listOf("id"),
        ) {
            put("id", integer("Element id from browser_read.", 0))
        },
        descriptor(
            "browser_type", DshCapabilityGroup.BROWSER, DshCapabilityRisk.WRITE, false,
            "Type into a browser input returned by browser_read.",
            listOf("id", "text"),
        ) {
            put("id", integer("Input element id from browser_read.", 0))
            put("text", string("Text to type."))
            put("submit", bool("Submit the field after typing."))
        },
        descriptor(
            "browser_scroll", DshCapabilityGroup.BROWSER, DshCapabilityRisk.READ, false,
            "Scroll the current browser page.",
            listOf("direction"),
        ) {
            put("direction", enum("Direction to scroll.", "down", "up", "top", "bottom"))
        },
        descriptor(
            "browser_back", DshCapabilityGroup.BROWSER, DshCapabilityRisk.READ, false,
            "Go back in browser history.",
        ),
        descriptor(
            "browser_screenshot", DshCapabilityGroup.BROWSER, DshCapabilityRisk.READ, false,
            "Capture the visible browser page.",
        ),

        // Files
        descriptor(
            "file_search", DshCapabilityGroup.FILES, DshCapabilityRisk.READ, false,
            "Find files in shared storage by file-name glob and/or literal text content.",
        ) {
            put("name", string("File-name glob such as *.pdf."))
            put("content", string("Case-insensitive literal text filter."))
            put("dir", string("Directory to search; defaults to shared storage."))
            put("limit", integer("Maximum results.", 1, 1_000))
            put("sort", enum("Result ordering.", "recent", "largest", "name"))
        },
        descriptor(
            "file_list", DshCapabilityGroup.FILES, DshCapabilityRisk.READ, false,
            "List a shared-storage directory with sizes and modification times.",
        ) {
            put("path", string("Directory path."))
            put("recursive", bool("Include subdirectories."))
            put("limit", integer("Maximum entries.", 1, 2_000))
        },
        descriptor(
            "file_read", DshCapabilityGroup.FILES, DshCapabilityRisk.READ, false,
            "Read text, an image, or the first supported part of a PDF or Office document.",
            listOf("path"),
        ) {
            put("path", string("File path."))
            put("offset", integer("First line to return.", 0))
            put("limit", integer("Maximum lines to return.", 1, 4_000))
        },
        descriptor(
            "file_stat", DshCapabilityGroup.FILES, DshCapabilityRisk.READ, false,
            "Read size and modification metadata for files or directories.",
            listOf("paths"),
        ) {
            put("paths", array("Paths to inspect.", stringSchema("A shared-storage path.")))
        },
        descriptor(
            "file_write", DshCapabilityGroup.FILES, DshCapabilityRisk.WRITE, true,
            "Create, overwrite, or append to a text file.",
            listOf("path"),
        ) {
            put("path", string("File path."))
            put("content", string("Text content; omitted content creates or truncates the file."))
            put("append", bool("Append instead of overwrite."))
        },
        descriptor(
            "file_mkdir", DshCapabilityGroup.FILES, DshCapabilityRisk.WRITE, true,
            "Create directories including parents.",
            listOf("paths"),
        ) {
            put("paths", array("Directories to create.", stringSchema("A directory path.")))
        },
        descriptor(
            "file_move", DshCapabilityGroup.FILES, DshCapabilityRisk.WRITE, true,
            "Move, rename, or copy files and folders.",
            listOf("items"),
        ) {
            put(
                "items",
                array(
                    "Move or copy operations.",
                    objectSchema(listOf("from", "to")) {
                        put("from", string("Source path."))
                        put("to", string("Destination path."))
                    },
                ),
            )
            put("copy", bool("Copy instead of move."))
        },
        descriptor(
            "file_delete", DshCapabilityGroup.FILES, DshCapabilityRisk.DANGEROUS, true,
            "Permanently delete exact files and folders under shared storage.",
            listOf("paths"),
        ) {
            put("paths", array("Exact paths to delete.", stringSchema("An exact path.")))
        },
        descriptor(
            "archive_create", DshCapabilityGroup.FILES, DshCapabilityRisk.WRITE, true,
            "Create a ZIP archive from files and folders.",
            listOf("paths", "output"),
        ) {
            put("paths", array("Archive inputs.", stringSchema("An input path.")))
            put("output", string("Output ZIP path."))
        },
        descriptor(
            "archive_extract", DshCapabilityGroup.FILES, DshCapabilityRisk.WRITE, true,
            "Extract a ZIP archive.",
            listOf("archive"),
        ) {
            put("archive", string("ZIP archive path."))
            put("dest", string("Destination directory."))
            put("overwrite", bool("Replace files that already exist."))
        },

        // Shell
        descriptor(
            "shell_run", DshCapabilityGroup.SHELL, DshCapabilityRisk.DANGEROUS, false,
            "Run one command through Solace's paired Android shell helper.",
            listOf("command"),
        ) {
            put("command", string("Android shell command."))
        },

        // Notifications
        descriptor(
            "notification_list", DshCapabilityGroup.NOTIFICATIONS, DshCapabilityRisk.SENSITIVE, false,
            "List active notifications, optionally filtered by package.",
        ) {
            put("package", string("Optional package name."))
            put("limit", integer("Maximum notifications.", 1, 100))
        },
        descriptor(
            "notification_dismiss", DshCapabilityGroup.NOTIFICATIONS, DshCapabilityRisk.WRITE, false,
            "Dismiss selected clearable notifications by key.",
            listOf("keys"),
        ) {
            put("keys", array("Notification keys.", stringSchema("A notification key.")))
        },
        descriptor(
            "notification_open", DshCapabilityGroup.NOTIFICATIONS, DshCapabilityRisk.WRITE, true,
            "Open a notification or invoke one of its actions.",
            listOf("key"),
        ) {
            put("key", string("Notification key."))
            put("action", string("Optional action title."))
        },
        descriptor(
            "notification_reply", DshCapabilityGroup.NOTIFICATIONS, DshCapabilityRisk.DANGEROUS, false,
            "Reply directly to a notification's remote input.",
            listOf("key", "text"),
        ) {
            put("key", string("Notification key."))
            put("text", string("Reply text."))
        },

        // Communications and calendar
        descriptor(
            "contacts_search", DshCapabilityGroup.COMMS, DshCapabilityRisk.SENSITIVE, false,
            "Search contacts by name or number.",
            listOf("query"),
        ) {
            put("query", string("Name or number fragment."))
        },
        descriptor(
            "sms_read", DshCapabilityGroup.COMMS, DshCapabilityRisk.SENSITIVE, false,
            "Read recent SMS inbox and sent messages.",
        ) {
            put("query", string("Filter on address or message body."))
            put("limit", integer("Maximum messages.", 1, 200))
        },
        descriptor(
            "call_log_read", DshCapabilityGroup.COMMS, DshCapabilityRisk.SENSITIVE, false,
            "Read recent calls.",
        ) {
            put("limit", integer("Maximum calls.", 1, 200))
        },
        descriptor(
            "calendar_read", DshCapabilityGroup.COMMS, DshCapabilityRisk.SENSITIVE, false,
            "Read calendar events in a time range.",
        ) {
            put("from", string("ISO-8601 start time or date."))
            put("to", string("ISO-8601 end time or date."))
            put("fromMs", integer("Start epoch milliseconds."))
            put("toMs", integer("End epoch milliseconds."))
        },
        descriptor(
            "calendar_add", DshCapabilityGroup.COMMS, DshCapabilityRisk.WRITE, false,
            "Add an event to the primary writable calendar; give start/startMs and an optional end/endMs.",
            listOf("title"),
        ) {
            put("title", string("Event title."))
            put("start", string("ISO-8601 start time or all-day date."))
            put("end", string("ISO-8601 end time or all-day date; defaults to one hour after start."))
            put("startMs", integer("Start epoch milliseconds."))
            put("endMs", integer("End epoch milliseconds."))
            put("location", string("Optional event location."))
            put("description", string("Optional event description."))
        },
        descriptor(
            "sms_send", DshCapabilityGroup.COMMS, DshCapabilityRisk.DANGEROUS, false,
            "Send an SMS after the recipient and text have been confirmed.",
            listOf("to", "text"),
        ) {
            put("to", string("Phone number."))
            put("text", string("Message text."))
        },

        // Clipboard
        descriptor(
            "clipboard_get", DshCapabilityGroup.CLIPBOARD, DshCapabilityRisk.SENSITIVE, false,
            "Read plain text from the clipboard when Android permits it.",
        ),
        descriptor(
            "clipboard_set", DshCapabilityGroup.CLIPBOARD, DshCapabilityRisk.WRITE, false,
            "Put plain text on the clipboard.",
            listOf("text"),
        ) {
            put("text", string("Text to copy."))
        },

        // Media
        descriptor(
            "media_control", DshCapabilityGroup.MEDIA, DshCapabilityRisk.WRITE, false,
            "Inspect or control the active media session.",
            listOf("action"),
        ) {
            put(
                "action",
                enum(
                    "Media action.",
                    "status", "play", "pause", "toggle", "next", "previous", "stop",
                ),
            )
        },

        // System
        descriptor(
            "device_info", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.READ, false,
            "Read time, Android/device identity, battery, network, storage, audio, DND, and lock state.",
            timeoutMs = READ_TIMEOUT_MS,
        ),
        descriptor(
            "sound_mode", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.WRITE, false,
            "Set the ringer mode.",
            listOf("mode"),
        ) {
            put("mode", enum("Ringer mode.", "normal", "vibrate", "silent"))
        },
        descriptor(
            "volume_set", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.WRITE, false,
            "Set an audio stream to a percentage of its maximum volume.",
            listOf("stream", "percent"),
        ) {
            put("stream", enum("Audio stream.", "music", "ring", "alarm", "notification", "call"))
            put("percent", integer("Volume percentage.", 0, 100))
        },
        descriptor(
            "dnd_set", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.WRITE, false,
            "Turn Do Not Disturb on or off.",
            listOf("on"),
        ) {
            put("on", bool("Whether Do Not Disturb should be on."))
        },
        descriptor(
            "brightness_set", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.WRITE, false,
            "Set manual screen brightness or enable adaptive brightness.",
        ) {
            put("percent", integer("Brightness percentage.", 0, 100))
            put("auto", bool("Enable adaptive brightness."))
        },
        descriptor(
            "flashlight", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.WRITE, false,
            "Turn the flashlight on or off.",
            listOf("on"),
        ) {
            put("on", bool("Whether the flashlight should be on."))
        },
        descriptor(
            "alarm_set", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.WRITE, true,
            "Create an alarm with hour/minute or start a timer with timerSeconds.",
        ) {
            put("hour", integer("Hour in 24-hour local time.", 0, 23))
            put("minute", integer("Minute.", 0, 59))
            put("timerSeconds", integer("Timer duration in seconds.", 1, 86_400))
            put("label", string("Optional alarm label."))
        },
        descriptor(
            "setting_get", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.READ, false,
            "Read one Android setting from the system, secure, or global namespace.",
            listOf("namespace", "key"),
        ) {
            put("namespace", enum("Settings namespace.", "system", "secure", "global"))
            put("key", string("Setting key."))
        },
        descriptor(
            "setting_put", DshCapabilityGroup.SYSTEM, DshCapabilityRisk.DANGEROUS, false,
            "Write one Android setting through Solace's Android shell.",
            listOf("namespace", "key", "value"),
        ) {
            put("namespace", enum("Settings namespace.", "system", "secure", "global"))
            put("key", string("Setting key."))
            put("value", string("Setting value."))
        },
    )

    init {
        require(all.map { it.name }.toSet().size == all.size) { "capability names must be unique" }
    }

    /** Exact payload for the bridge's `capability/list` response. */
    fun capabilityListJson(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            all.forEach { add(it.toJson()) }
        }
    }

    /**
     * Lossless or explicitly restricted aliases to Solace's native tools. Device-only capabilities
     * have no alias and are dispatched by the Android bridge itself.
     */
    val solaceAliases: Map<String, String> = mapOf(
        "screen_read" to "phone_list_elements",
        "screen_capture" to "phone_screenshot",
        "screen_tap" to "phone_tap",
        "screen_type" to "phone_type_text",
        "screen_swipe" to "phone_swipe",
        "screen_scroll" to "phone_scroll",
        "screen_key" to "phone_press_key",
        "screen_wait" to "phone_wait",
        "app_list" to "phone_list_apps",
        "app_launch" to "phone_launch_app",
        "app_open_link" to "phone_open_link",
        "share_to_app" to "phone_share_text",
        "browser_open" to "web_navigate",
        "browser_read" to "web_read_page",
        "browser_click" to "web_tap",
        "browser_type" to "web_type_text",
        "browser_scroll" to "web_scroll",
        "browser_back" to "web_back",
        "browser_screenshot" to "web_screenshot",
        "file_read" to "read_file",
        "file_write" to "write_file",
        "file_search" to "search_files",
        "shell_run" to "phone_system_command",
    )

    fun solaceAlias(name: String): String? = solaceAliases[name]

    /**
     * Adapt a DSH capability call to Solace's dispatcher contract. A null result means the call
     * cannot be represented without dropping arguments that change its meaning.
     */
    fun normalizeSolaceCall(name: String, args: JsonObject): SolaceCapabilityCall? {
        if (name !in solaceAliases) return null
        return when (name) {
            "screen_read" -> {
                if (args.boolean("ocr") == true) return null
                SolaceCapabilityCall("phone_list_elements", emptyArgs())
            }
            "screen_capture" -> SolaceCapabilityCall("phone_screenshot", emptyArgs())
            "screen_tap" -> SolaceCapabilityCall(
                "phone_tap",
                buildJsonObject {
                    args["node"]?.let { put("ref", it) }
                    args["text"]?.let { put("describedElement", it) }
                    args["x"]?.let { put("x", it) }
                    args["y"]?.let { put("y", it) }
                },
            )
            "screen_type" -> {
                if (args.boolean("append") == true) return null
                SolaceCapabilityCall(
                    "phone_type_text",
                    buildJsonObject {
                        args["text"]?.let { put("text", it) }
                        args["node"]?.let { put("ref", it) }
                        args["submit"]?.let { put("pressEnter", it) }
                    },
                )
            }
            "screen_swipe" -> SolaceCapabilityCall(
                "phone_swipe",
                buildJsonObject {
                    rename(args, "x1", "startX")
                    rename(args, "y1", "startY")
                    rename(args, "x2", "endX")
                    rename(args, "y2", "endY")
                    args["durationMs"]?.let { put("durationMs", it) }
                },
            )
            "screen_scroll" -> SolaceCapabilityCall(
                "phone_scroll",
                buildJsonObject { args["direction"]?.let { put("direction", it) } },
            )
            "screen_key" -> {
                val key = when (args.string("key")) {
                    "enter", "search" -> "KEYCODE_ENTER"
                    "delete" -> "KEYCODE_DEL"
                    "back" -> "KEYCODE_BACK"
                    else -> return null
                }
                SolaceCapabilityCall("phone_press_key", buildJsonObject { put("key", key) })
            }
            "screen_wait" -> {
                val callArgs = buildJsonObject {
                    when {
                        args.string("text") != null -> {
                            put("condition", if (args.boolean("gone") == true) "disappear" else "appear")
                            args["text"]?.let { put("value", it) }
                        }
                        args.string("package") != null -> {
                            put("condition", "activity")
                            args["package"]?.let { put("value", it) }
                        }
                        else -> return null
                    }
                    args["timeoutMs"]?.let { put("timeoutMs", it) }
                }
                SolaceCapabilityCall("phone_wait", callArgs)
            }
            "app_list" -> SolaceCapabilityCall("phone_list_apps", emptyArgs())
            "app_launch" -> SolaceCapabilityCall(
                "phone_launch_app",
                buildJsonObject {
                    args["package"]?.let { put("package", it) }
                    args["cold"]?.let { put("cold", it) }
                },
            )
            "app_open_link" -> SolaceCapabilityCall(
                "phone_open_link",
                buildJsonObject {
                    args["uri"]?.let { put("uri", it) }
                    args["package"]?.let { put("package", it) }
                },
            )
            "share_to_app" -> {
                if ((args["paths"] as? JsonArray)?.isNotEmpty() == true) return null
                SolaceCapabilityCall(
                    "phone_share_text",
                    buildJsonObject {
                        args["package"]?.let { put("package", it) }
                        args["text"]?.let { put("text", it) }
                    },
                )
            }
            "browser_open" -> SolaceCapabilityCall(
                "web_navigate",
                buildJsonObject { args["url"]?.let { put("url", it) } },
            )
            "browser_read" -> SolaceCapabilityCall(
                "web_read_page",
                buildJsonObject { args["maxChars"]?.let { put("maxChars", it) } },
            )
            "browser_click" -> SolaceCapabilityCall(
                "web_tap",
                buildJsonObject { args["id"]?.let { put("ref", it) } },
            )
            "browser_type" -> SolaceCapabilityCall(
                "web_type_text",
                buildJsonObject {
                    args["id"]?.let { put("ref", it) }
                    args["text"]?.let { put("text", it) }
                    args["submit"]?.let { put("pressEnter", it) }
                },
            )
            "browser_scroll" -> SolaceCapabilityCall(
                "web_scroll",
                buildJsonObject { args["direction"]?.let { put("direction", it) } },
            )
            "browser_back" -> SolaceCapabilityCall("web_back", emptyArgs())
            "browser_screenshot" -> SolaceCapabilityCall("web_screenshot", emptyArgs())
            "file_read" -> SolaceCapabilityCall(
                "read_file",
                buildJsonObject {
                    args["path"]?.let { put("path", it) }
                    args["offset"]?.let { put("offset", it) }
                    args["limit"]?.let { put("limit", it) }
                },
            )
            "file_write" -> SolaceCapabilityCall(
                "write_file",
                buildJsonObject {
                    args["path"]?.let { put("path", it) }
                    args["content"]?.let { put("content", it) }
                    args["append"]?.let { put("append", it) }
                },
            )
            "file_search" -> SolaceCapabilityCall(
                "search_files",
                buildJsonObject {
                    args["content"]?.let { put("pattern", it) }
                    if (args["content"] == null) put("pattern", ".")
                    args["name"]?.let { put("glob", it) }
                    args["dir"]?.let { put("path", it) }
                },
            )
            "shell_run" -> SolaceCapabilityCall(
                "phone_system_command",
                buildJsonObject { args["command"]?.let { put("command", it) } },
            )
            else -> null
        }
    }

    private fun descriptor(
        name: String,
        group: DshCapabilityGroup,
        risk: DshCapabilityRisk,
        needsUnlock: Boolean,
        description: String,
        required: List<String> = emptyList(),
        timeoutMs: Long = if (needsUnlock) UNLOCK_TIMEOUT_MS else DEFAULT_TIMEOUT_MS,
        properties: JsonObjectBuilder.() -> Unit = {},
    ) = DshCapabilityDescriptor(
        name = name,
        group = group,
        description = description,
        inputSchema = schema(required, properties),
        risk = risk,
        needsUnlock = needsUnlock,
        timeoutMs = timeoutMs,
    )

    private fun schema(
        required: List<String>,
        properties: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", properties)
        putJsonArray("required") { required.forEach(::add) }
        put("additionalProperties", false)
    }

    private fun objectSchema(
        required: List<String>,
        properties: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", properties)
        putJsonArray("required") { required.forEach(::add) }
        put("additionalProperties", false)
    }

    private fun array(
        description: String,
        itemSchema: JsonObject,
        minItems: Int? = null,
        maxItems: Int? = null,
    ): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", itemSchema)
        minItems?.let { put("minItems", it) }
        maxItems?.let { put("maxItems", it) }
    }

    private fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun string(description: String): JsonObject = stringSchema(description)

    private fun bool(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    private fun enum(description: String, vararg values: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun objectProperty(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", true)
    }

    private fun emptyArgs() = JsonObject(emptyMap())

    private fun JsonObjectBuilder.rename(source: JsonObject, from: String, to: String) {
        source[from]?.let { put(to, it) }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let {
            when (it.contentOrNull) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}

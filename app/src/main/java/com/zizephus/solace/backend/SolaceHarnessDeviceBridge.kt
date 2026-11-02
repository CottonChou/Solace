package com.zizephus.solace.backend

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.View
import android.view.Display
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zizephus.solace.SolaceApp
import com.zizephus.solace.cap.phone.a11y.AndroidScreen
import com.zizephus.solace.cap.phone.a11y.ScreenAccessibility
import com.zizephus.solace.cap.phone.display.AgentDisplay
import com.zizephus.solace.cap.phone.display.LazyAgentDisplay
import com.zizephus.solace.cap.phone.shell.ShellBinding
import com.zizephus.solace.core.shell.AccessibilityRepair
import com.zizephus.solace.core.uistate.ActionResult
import com.zizephus.solace.core.uistate.ScreenPort
import com.zizephus.solace.core.uistate.ScreenSnapshot
import com.zizephus.solace.core.uistate.UiElement
import com.zizephus.solace.harness.core.engine.SolaceHarnessCapabilityDescriptors
import com.zizephus.solace.harness.core.engine.SolaceHarnessEngineCallbacks
import com.zizephus.solace.harness.core.engine.JsonRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.hypot

/**
 * DSH capability channel backed by Solace's own device services.
 *
 * This is deliberately an adapter at the host boundary. The engine keeps its normal tool and
 * approval loop; every device operation still uses Solace's managed virtual display, permission
 * checks, shared shell and task lifecycle.
 */
class SolaceHarnessDeviceBridge(private val app: SolaceApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val deviceLock = Mutex()
    private val policy = HostSafetyPolicy(ownAppPackage = app.packageName)
    private val handoverChannel = SolaceHarnessHandoverChannel()

    /** 引擎开跑一个会话时登记接管通道;结束时由 [cancelSession]/[releaseDevice] 收尾。 */
    fun connectSession(sessionId: String) {
        handoverChannel.connect(sessionId)
    }

    suspend fun requestHandover(sessionId: String, reason: String): HandoverResult =
        handoverChannel.wait(sessionId, reason)

    fun completeHandover(sessionId: String, outcome: String) {
        handoverChannel.complete(sessionId, outcome)
    }

    /** 任务级授权在没有会话号(测试、裸调用)时的兜底任务号。 */
    private val DEFAULT_POLICY_TASK_ID = "harness-device"

    /** 从调用参数里挑出这一步作用在哪个 App 上;拿不到就是 null(按未知包处理)。 */
    private fun targetPackage(name: String, args: JsonObject): String? = when {
        args["package"] is JsonPrimitive -> args.str("package")
        else -> null
    }

    /** 需要确认时走 DSH 自己的审批流:复用风险映射,不另起一套。 */
    private fun confirmWithUser(name: String, reason: String, packageName: String?): JsonElement {
        val request = dshConfirmationRequest(
            tool = name,
            reason = reason,
            packageName = packageName ?: app.packageName,
        )
        return errorResult("policy_needs_confirmation", "confirm:${request.target}:${request.content}")
    }

    @Volatile private var binding: ShellBinding? = null
    @Volatile private var display: LazyAgentDisplay? = null
    @Volatile private var phone: ScreenPort? = null
    @Volatile private var web: WebView? = null
    @Volatile private var webStartedAt = 0L
    @Volatile private var webFinishedAt = 0L

    fun callbacks(): SolaceHarnessEngineCallbacks = SolaceHarnessEngineCallbacks(
        onSdkRequest = { method, _ -> throw JsonRpcException(-32601, "DSH SDK request is not a device capability: $method") },
        onCapabilityRequest = ::request,
        onCapabilityNotification = { method, params ->
            if (method == "capability/cancel") cancel(params.str("callId"), params.str("sessionId"))
        },
    )

    private suspend fun request(method: String, params: JsonObject): JsonElement = when (method) {
        "capability/list" -> SolaceHarnessCapabilityDescriptors.capabilityListJson()
        "capability/invoke" -> invoke(params)
        "capability/cancel" -> {
            cancel(params.str("callId"), params.str("sessionId"))
            buildJsonObject { put("ok", true) }
        }
        else -> throw JsonRpcException(-32601, "unknown capability method $method")
    }

    private suspend fun invoke(params: JsonObject): JsonElement {
        val callId = params.str("callId") ?: UUID.randomUUID().toString()
        val sessionId = params.str("sessionId").orEmpty()
        val key = callKey(sessionId, callId)
        val job = scope.async(currentCoroutineContext()[Job] ?: EmptyCoroutineContext) {
            runTool(
                sessionId,
                params.reqStr("tool"),
                params["args"] as? JsonObject ?: JsonObject(emptyMap()),
                params.long("timeoutMs"),
            )
        }
        inFlight.put(key, job)?.cancel(CancellationException("superseded capability call"))
        return try {
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            errorResult("cancelled", "The call was cancelled before it acted.")
        } finally {
            inFlight.remove(key, job)
        }
    }

    fun cancel(callId: String?, sessionId: String? = null) {
        if (callId == null) return
        if (sessionId != null) {
            inFlight.remove(callKey(sessionId, callId))?.cancel(CancellationException("cancelled by DSH"))
            return
        }
        inFlight.keys.filter { it.endsWith("\u0000$callId") }
            .forEach { inFlight.remove(it)?.cancel(CancellationException("cancelled by DSH")) }
    }

    fun cancelSession(sessionId: String) {
        handoverChannel.cancel(sessionId)
        inFlight.keys.filter { it.substringBefore('\u0000') == sessionId }
            .forEach { inFlight.remove(it)?.cancel(CancellationException("session stopped")) }
    }

    suspend fun releaseDevice() = deviceLock.withLock {
        runCatching { web?.destroy() }
        web = null
        phone = null
        display?.current?.release()
        display = null
        binding?.let { app.phone.sharedShell.release() }
        binding = null
        app.resources.releaseOwner(DEVICE_RUN_KEY)
        app.taskRecovery.endRun(DEVICE_RUN_KEY)
        handoverChannel.clear()
    }

    private suspend fun runTool(sessionId: String, name: String, args: JsonObject, requestedTimeout: Long?): JsonElement {
        val taskId = sessionId.ifBlank { DEFAULT_POLICY_TASK_ID }
        val packageName = targetPackage(name, args)
        when (val verdict = policy.evaluate(name, packageName, taskId)) {
            is SafetyVerdict.Denied -> return errorResult("policy_denied", verdict.reason)
            is SafetyVerdict.Confirm -> return confirmWithUser(name, verdict.reason, packageName)
            is SafetyVerdict.Allowed -> {}
        }
        val timeout = (requestedTimeout ?: descriptorTimeout(name)).coerceIn(1_000, 900_000)
        return withTimeoutOrNull((timeout - 2_000).coerceAtLeast(500)) {
            try {
                dispatch(name, args)
        } catch (e: TimeoutCancellationException) {
            errorResult("handover_timeout", "The user did not hand the task back in time.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingPermission) {
                errorResult("permission_missing", e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                errorResult("invalid_args", e.message ?: "invalid arguments")
            } catch (e: Exception) {
                errorResult("failed", "${e.javaClass.simpleName}: ${e.message}")
            }
        } ?: errorResult("timeout", "The device call did not finish within ${timeout / 1000}s.")
    }

    private suspend fun dispatch(name: String, args: JsonObject): JsonElement = when (name) {
        "screen_read" -> screenRead(args)
        "screen_capture" -> screenCapture()
        "screen_tap" -> screenTap(args)
        "screen_type" -> screenType(args)
        "screen_swipe" -> screenSwipe(args)
        "screen_scroll" -> screenScroll(args)
        "screen_key" -> screenKey(args)
        "screen_wait" -> screenWait(args)
        "hand_to_user" -> handoverJson(
            handoverChannel.wait(
                args.str("sessionId"),
                args.str("reason") ?: "This step needs the user to handle it on the phone.",
            ),
        )

        "app_list" -> appList(args)
        "app_info" -> appInfo(args)
        "app_launch" -> appLaunch(args)
        "app_open_link" -> appOpenLink(args)
        "app_send_intent" -> sendIntent(args)
        "share_to_app" -> shareToApp(args)

        "file_search" -> fileSearch(args)
        "file_list" -> fileList(args)
        "file_read" -> fileRead(args)
        "file_stat" -> fileStat(args)
        "file_write" -> fileWrite(args)
        "file_mkdir" -> fileMkdir(args)
        "file_move" -> fileMove(args)
        "file_delete" -> fileDelete(args)
        "archive_create" -> archiveCreate(args)
        "archive_extract" -> archiveExtract(args)

        "browser_open" -> browserOpen(args)
        "browser_read" -> browserRead(args)
        "browser_click" -> browserClick(args)
        "browser_type" -> browserType(args)
        "browser_scroll" -> browserScroll(args)
        "browser_back" -> browserBack()
        "browser_screenshot" -> browserScreenshot()

        "notification_list" -> notificationList(args)
        "notification_dismiss" -> notificationDismiss(args)
        "notification_open" -> notificationOpen(args)
        "notification_reply" -> notificationReply(args)

        "contacts_search" -> contactsSearch(args)
        "sms_read" -> smsRead(args)
        "call_log_read" -> callLogRead(args)
        "calendar_read" -> calendarRead(args)
        "calendar_add" -> calendarAdd(args)
        "sms_send" -> smsSend(args)
        "media_control" -> mediaControl(args)
        "clipboard_get" -> clipboardGet()
        "clipboard_set" -> clipboardSet(args)

        "device_info" -> deviceInfo()
        "sound_mode" -> soundMode(args)
        "volume_set" -> volumeSet(args)
        "dnd_set" -> dndSet(args)
        "brightness_set" -> brightnessSet(args)
        "flashlight" -> flashlight(args)
        "alarm_set" -> alarmSet(args)
        "setting_get" -> settingGet(args)
        "setting_put" -> settingPut(args)
        "shell_run" -> shellRun(args)
        else -> errorResult("unsupported", "Unknown device capability: $name")
    }

    private suspend fun screenRead(args: JsonObject): JsonElement {
        val screen = device()
        val snapshot = screen.snapshot()
        val lines = snapshot.elements.joinToString("\n") { element ->
            buildString {
                append('[').append(element.ref).append("] ")
                append(element.role)
                if (element.name.isNotBlank()) append(" \"").append(element.name.take(240)).append('"')
                append(" @").append(element.centerX).append(',').append(element.centerY)
                val flags = buildList {
                    if (element.clickable) add("clickable")
                    if (element.editable) add("editable")
                    if (element.scrollable) add("scrollable")
                    if (element.focused) add("focused")
                    if (element.secret) add("secret")
                    if (element.states.isNotEmpty()) add(element.states.joinToString("+"))
                }
                if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
                element.resourceId?.let { append(" #").append(it) }
            }
        }
        val ocr = if (args.bool("ocr") == true || snapshot.elements.isEmpty()) {
            screen.readVisibleText().orEmpty().mapIndexed { i, line ->
                "[t${i + 1}] \"${line.text}\" @${centerOf(line.bounds).first},${centerOf(line.bounds).second}"
            }.joinToString("\n")
        } else ""
        val body = listOfNotNull(
            "App: ${snapshot.packageName ?: "(none)"} · snapshot ${snapshot.snapshotId} · ${snapshot.width}x${snapshot.height} px",
            lines.takeIf { it.isNotBlank() },
            ocr.takeIf { it.isNotBlank() }?.let { "-- OCR --\n$it" },
        ).joinToString("\n")
        return textResult(body.ifBlank { "The screen has no readable elements." })
    }

    private suspend fun screenCapture(): JsonElement {
        val bytes = device().screenshotPng() ?: return errorResult(
            "screenshot_unavailable",
            "No frame is available yet, or the page blocks screenshots.",
        )
        return imageResult("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private suspend fun screenTap(args: JsonObject): JsonElement {
        val screen = device()
        val snapshot = screen.snapshot()
        val element = resolveElement(args, snapshot)
        val action = when {
            element != null -> screen.tapRef(element.ref, snapshot.snapshotId)
            args.int("x") != null && args.int("y") != null -> screen.tapXY(args.int("x")!!, args.int("y")!!)
            else -> throw IllegalArgumentException("Give node, text, or x/y.")
        }
        return actionResult("Tapped", action)
    }

    private suspend fun screenType(args: JsonObject): JsonElement {
        val text = args.reqStr("text")
        val screen = device()
        val snapshot = screen.snapshot()
        val element = resolveElement(args, snapshot)
            ?: snapshot.elements.firstOrNull { it.focused && it.editable }
        if (!args.bool("append").orFalse() && (element != null || snapshot.snapshotId.isNotBlank())) {
            screen.clearText(element?.ref, snapshot.snapshotId)
        }
        val action = screen.typeText(element?.ref, snapshot.snapshotId, text)
        if (action !is ActionResult.Ok) return actionResult("Could not type", action)
        if (args.bool("submit") == true) {
            val submitted = screen.pressKey(KeyEvent.KEYCODE_ENTER)
            return actionResult("Entered text and submitted", submitted)
        }
        return actionResult("Entered text", action)
    }

    private suspend fun screenSwipe(args: JsonObject): JsonElement {
        val duration = (args.int("durationMs") ?: 300).coerceIn(50, 5_000).toLong()
        return actionResult(
            "Swiped",
            device().swipe(args.reqInt("x1"), args.reqInt("y1"), args.reqInt("x2"), args.reqInt("y2"), duration),
        )
    }

    private suspend fun screenScroll(args: JsonObject): JsonElement {
        val screen = device()
        val direction = args.reqStr("direction")
        if (direction == "left" || direction == "right") {
            val metrics = metrics()
            val cy = metrics.heightPixels / 2
            val dx = (metrics.widthPixels * 0.3f).toInt()
            val cx = metrics.widthPixels / 2
            return actionResult(
                "Scrolled $direction",
                if (direction == "right") screen.swipe(cx + dx, cy, cx - dx, cy, 350)
                else screen.swipe(cx - dx, cy, cx + dx, cy, 350),
            )
        }
        val snapshot = screen.snapshot()
        val ref = args.str("node")
        return actionResult("Scrolled $direction", screen.scroll(ref, snapshot.snapshotId, direction == "down"))
    }

    private suspend fun screenKey(args: JsonObject): JsonElement {
        val key = args.reqStr("key")
        val code = when (key) {
            "enter" -> KeyEvent.KEYCODE_ENTER
            "search" -> KeyEvent.KEYCODE_SEARCH
            "delete" -> KeyEvent.KEYCODE_DEL
            "back" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH
            "notifications" -> KeyEvent.KEYCODE_NOTIFICATION
            else -> throw IllegalArgumentException("This build supports enter/search/delete/back/home/recents/notifications.")
        }
        return actionResult("Pressed $key", device().pressKey(code))
    }

    private suspend fun screenWait(args: JsonObject): JsonElement {
        val text = args.str("text")
        val packageName = args.str("package")
        require(text != null || packageName != null) { "Give text and/or package." }
        val until = SystemClock.elapsedRealtime() + (args.int("timeoutMs") ?: 10_000).coerceIn(500, 30_000)
        val screen = device()
        while (SystemClock.elapsedRealtime() < until) {
            currentCoroutineContext().ensureActive()
            val snap = screen.snapshot()
            val has = text == null || snap.elements.any {
                it.name.contains(text, ignoreCase = true) || it.resourceId?.contains(text, ignoreCase = true) == true
            }
            val ok = (packageName == null || snap.packageName == packageName) &&
                (if (args.bool("gone") == true && text != null) !has else has)
            if (ok) return textResult("Condition met.")
            delay(500)
        }
        return textResult("Timed out waiting.")
    }

    private suspend fun appList(args: JsonObject): JsonElement {
        val query = args.str("query")?.lowercase()
        val apps = device().listApps().filter { (pkg, label) ->
            query == null || label.lowercase().contains(query) || pkg.lowercase().contains(query)
        }.sortedBy { it.second.lowercase() }
        return textResult(apps.joinToString("\n") { "${it.second}  (${it.first})" }.ifEmpty { "No matching apps." })
    }

    private fun appInfo(args: JsonObject): JsonElement {
        val pkg = args.reqStr("package")
        val info = try {
            app.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        } catch (_: PackageManager.NameNotFoundException) {
            return errorResult("not_found", "Not installed: $pkg")
        }
        val ai = info.applicationInfo ?: return errorResult("failed", "App info is unavailable.")
        return textResult(
            buildString {
                appendLine("${app.packageManager.getApplicationLabel(ai)} ($pkg) ${info.versionName}")
                appendLine("installed ${Date(info.firstInstallTime)}, updated ${Date(info.lastUpdateTime)}")
                appendLine("apk ${File(ai.sourceDir).length() / 1024 / 1024} MB, system=${ai.flags and ApplicationInfo.FLAG_SYSTEM != 0}")
                append("exported activities: ")
                append(info.activities?.filter { it.exported }?.take(30)?.joinToString { it.name } ?: "-")
            },
        )
    }

    private suspend fun appLaunch(args: JsonObject): JsonElement {
        val action = device().openApp(args.reqStr("package"), cold = args.bool("cold") == true)
        return actionResult("Launched app", action)
    }

    private suspend fun appOpenLink(args: JsonObject): JsonElement {
        val action = device().openLink(null, args.reqStr("uri"), args.str("package"))
        return actionResult("Opened link", action)
    }

    private suspend fun sendIntent(args: JsonObject): JsonElement {
        val intent = Intent(args.reqStr("action")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.str("data")?.let { intent.data = Uri.parse(it) }
        args.str("type")?.let { intent.type = it }
        args.str("package")?.let { intent.setPackage(it) }
        (args["extras"] as? JsonObject)?.forEach { (key, value) ->
            val p = value as? JsonPrimitive ?: return@forEach
            when {
                p.isString -> intent.putExtra(key, p.content)
                p.booleanOrNull != null -> intent.putExtra(key, p.booleanOrNull)
                p.intOrNull != null -> intent.putExtra(key, p.intOrNull)
                p.longOrNull != null -> intent.putExtra(key, p.longOrNull)
                p.doubleOrNull != null -> intent.putExtra(key, p.doubleOrNull)
            }
        }
        return withContext(Dispatchers.IO) {
            try {
                app.startActivity(intent)
                textResult("Intent sent.")
            } catch (e: Exception) {
                errorResult("failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun shareToApp(args: JsonObject): JsonElement {
        val text = args.str("text")
        val pkg = args.str("package")
        val paths = args.strs("paths")
        if (paths.isEmpty()) {
            if (text == null) return errorResult("invalid_args", "Give text or paths.")
            if (pkg != null) return actionResult("Share sheet opened", device().shareText(pkg, text))
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return textResult("Share sheet opened.")
        }
        val files = paths.map(::sharedFile)
        files.firstOrNull { !it.isFile }?.let { return errorResult("invalid_args", "Not a file: ${it.path}") }
        val uris = ArrayList(files.map { file ->
            try {
                FileProvider.getUriForFile(app, "${app.packageName}.attachfiles", file)
            } catch (e: IllegalArgumentException) {
                return errorResult("invalid_args", "Cannot share ${file.path}: it is outside configured FileProvider paths.")
            }
        })
        val types = files.map {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) ?: "application/octet-stream"
        }.distinct()
        val mime = when {
            types.size == 1 -> types[0]
            types.map { it.substringBefore('/') }.distinct().size == 1 -> types[0].substringBefore('/') + "/*"
            else -> "*/*"
        }
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            pkg?.let(::setPackage)
        }
        val target = if (pkg == null) Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
        return try {
            app.startActivity(target)
            textResult("Share sheet opened.")
        } catch (e: Exception) {
            errorResult("failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fileSearch(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val root = sharedFile(args.str("dir") ?: Environment.getExternalStorageDirectory().path)
        if (!root.isDirectory) return@withContext errorResult("invalid_args", "Not a directory: ${root.path}")
        val glob = args.str("name")?.let(::simpleGlob) ?: Regex(".*")
        val content = args.str("content")?.takeIf { it.isNotBlank() }?.lowercase()
        val limit = (args.int("limit") ?: 100).coerceIn(1, 1_000)
        val files = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { !it.path.contains("/Android/data") && !it.path.contains("/Android/obb") }
            .filter { it.isFile && glob.matches(it.name) }
            .take(20_000)
            .forEach { file ->
                if (content == null || runCatching {
                    file.length() <= 8L * 1024 * 1024 && file.inputStream().bufferedReader().use { it.readText().lowercase().contains(content) }
                }.getOrDefault(false)) {
                    files += file
                }
            }
        val sorted = when (args.str("sort")) {
            "largest" -> files.sortedByDescending(File::length)
            "name" -> files.sortedBy { it.name.lowercase() }
            else -> files.sortedByDescending(File::lastModified)
        }
        textResult(
            if (sorted.isEmpty()) "No files found."
            else "${sorted.size} match(es):\n" + sorted.take(limit).joinToString("\n") { describeFile(it) },
        )
    }

    private suspend fun fileList(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val dir = sharedFile(args.str("path") ?: Environment.getExternalStorageDirectory().path)
        if (!dir.isDirectory) return@withContext errorResult("invalid_args", "Not a directory: ${dir.path}")
        val limit = (args.int("limit") ?: 300).coerceIn(1, 2_000)
        val entries = if (args.bool("recursive") == true) {
            dir.walkTopDown().drop(1).take(limit).toList()
        } else {
            dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.take(limit).orEmpty()
        }
        textResult(entries.joinToString("\n", transform = ::describeFile).ifEmpty { "(empty)" })
    }

    private suspend fun fileRead(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val file = sharedFile(args.reqStr("path"))
        if (!file.isFile) return@withContext errorResult("invalid_args", "Not a file: ${file.path}")
        val ext = file.extension.lowercase()
        if (ext in setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "avif")) {
            val bytes = imageBytes(file) ?: return@withContext textResult(
                "Image file (${file.length()} bytes), but Android cannot decode it.",
            )
            return@withContext imageResult("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), describeFile(file))
        }
        if (ext == "pdf") {
            return@withContext textResult(
                "PDF text extraction is available through Solace's Linux workspace, not this lightweight file reader: ${file.path}",
            )
        }
        if (ext in setOf("docx", "xlsx", "pptx")) {
            val text = runCatching { officeText(file) }.getOrNull()
                ?: return@withContext errorResult("unsupported", "Cannot extract text from ${file.name}.")
            return@withContext textResult(text.take(100_000))
        }
        val head = file.inputStream().use { input -> ByteArray(4_096).let { input.read(it).coerceAtLeast(0).let(it::copyOf) } }
        if (head.contains(0.toByte())) return@withContext textResult("Binary file (${file.length()} bytes).")
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (args.int("limit") ?: 400).coerceIn(1, 4_000)
        val lines = file.bufferedReader().useLines { it.drop(offset).take(limit).toList() }
        textResult(
            lines.joinToString("\n").take(100_000) +
                if (lines.size >= limit) "\n… use offset=${offset + limit} for more." else "",
        )
    }

    private suspend fun fileStat(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val paths = args.strs("paths")
        if (paths.isEmpty()) return@withContext errorResult("invalid_args", "Give paths.")
        textResult(paths.joinToString("\n") { raw ->
            val file = sharedFile(raw)
            if (!file.exists()) "$raw: not found"
            else if (file.isDirectory) {
                val files = file.walkTopDown().filter(File::isFile).toList()
                "${file.path}/: folder, ${files.size} files, ${humanBytes(files.sumOf(File::length))}"
            } else describeFile(file)
        })
    }

    private suspend fun fileWrite(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val file = sharedFile(args.reqStr("path"))
        file.parentFile?.mkdirs()
        if (args.bool("append") == true) file.appendText(args.str("content").orEmpty())
        else file.writeText(args.str("content").orEmpty())
        textResult("Wrote ${file.path} (${humanBytes(file.length())}).")
    }

    private suspend fun fileMkdir(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val paths = args.strs("paths")
        if (paths.isEmpty()) return@withContext errorResult("invalid_args", "Give paths.")
        textResult(paths.joinToString("\n") {
            val file = sharedFile(it)
            if (file.mkdirs() || file.isDirectory) "ok ${file.path}" else "failed ${file.path}"
        })
    }

    private suspend fun fileMove(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val items = (args["items"] as? JsonArray).orEmpty()
        if (items.isEmpty()) return@withContext errorResult("invalid_args", "Give items.")
        val copy = args.bool("copy") == true
        textResult(items.joinToString("\n") { item ->
            val obj = item as? JsonObject ?: return@joinToString "failed: item is not an object"
            runCatching {
                val from = sharedFile(obj.reqStr("from"))
                if (!from.exists()) return@runCatching "failed ${from.path}: not found"
                val rawTo = obj.reqStr("to")
                var to = sharedFile(rawTo)
                if (rawTo.endsWith("/") || to.isDirectory) {
                    to.mkdirs()
                    to = File(to, from.name)
                }
                if (to == from) return@runCatching "skip ${from.path}: same path"
                if (to.exists()) return@runCatching "skip ${from.path}: ${to.path} exists"
                to.parentFile?.mkdirs()
                if (copy) {
                    from.copyRecursively(to, overwrite = false)
                    "copied ${from.path} → ${to.path}"
                } else if (from.renameTo(to)) {
                    "moved ${from.path} → ${to.path}"
                } else {
                    from.copyRecursively(to, overwrite = false)
                    from.deleteRecursively()
                    "moved ${from.path} → ${to.path}"
                }
            }.getOrElse { e -> "failed: ${e.message}" }
        })
    }

    private suspend fun fileDelete(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val paths = args.strs("paths")
        if (paths.isEmpty()) return@withContext errorResult("invalid_args", "Give paths.")
        textResult(paths.joinToString("\n") { raw ->
            val file = sharedFile(raw)
            when {
                file == root || root.path.startsWith("${file.path}/") -> "refused: ${file.path} is a storage root"
                !file.exists() -> "not found ${file.path}"
                file.deleteRecursively() -> "deleted ${file.path}"
                else -> "partially deleted ${file.path}"
            }
        })
    }

    private suspend fun archiveCreate(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val inputs = args.strs("paths").map(::sharedFile)
        if (inputs.isEmpty()) return@withContext errorResult("invalid_args", "Give input paths.")
        inputs.firstOrNull { !it.exists() }?.let {
            return@withContext errorResult("invalid_args", "Not found: ${it.path}")
        }
        val output = sharedFile(args.reqStr("output"))
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, ".${output.name}.partial")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                inputs.forEach { input ->
                    val base = input.parentFile ?: output.parentFile
                    input.walkTopDown().forEach { file ->
                        if (file == output || file == temp) return@forEach
                        val name = file.relativeTo(base).path + if (file.isDirectory) "/" else ""
                        zip.putNextEntry(ZipEntry(name))
                        if (file.isFile) file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            if (!temp.renameTo(output)) {
                output.delete()
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            textResult("Created ${output.path} (${humanBytes(output.length())}).")
        } finally {
            temp.delete()
        }
    }

    private suspend fun archiveExtract(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requireAllFiles()
        val archive = sharedFile(args.reqStr("archive"))
        if (!archive.isFile) return@withContext errorResult("invalid_args", "Not a file: ${archive.path}")
        val dest = sharedFile(args.str("dest") ?: File(archive.parentFile, archive.nameWithoutExtension).path).apply { mkdirs() }
        var extracted = 0
        var skipped = 0
        val overwrite = args.bool("overwrite") == true
        val zip = runCatching { ZipFile(archive, Charsets.UTF_8) }
            .getOrElse { ZipFile(archive, java.nio.charset.Charset.forName("GBK")) }
        zip.use {
            it.entries().asSequence().forEach { entry ->
                val out = File(dest, entry.name).canonicalFile
                if (!out.path.startsWith(dest.canonicalPath + File.separator)) {
                    skipped++
                    return@forEach
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (out.exists() && !overwrite) {
                    skipped++
                } else {
                    out.parentFile?.mkdirs()
                    it.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                    extracted++
                }
            }
        }
        textResult("Extracted $extracted files to ${dest.path}; skipped $skipped.")
    }

    private suspend fun browserOpen(args: JsonObject): JsonElement {
        var url = args.reqStr("url")
        if (!url.contains("://")) url = "https://$url"
        webStartedAt = 0
        webFinishedAt = 0
        withContext(Dispatchers.Main) { browserView().loadUrl(url) }
        val loaded = withTimeoutOrNull(20_000) {
            while (webStartedAt == 0L || webFinishedAt < webStartedAt) delay(100)
        } != null
        delay(500)
        val note = if (loaded) "" else "(the page was still loading after 20s; content may be partial)\n"
        return textResult(note + browserPageText(40_000))
    }

    private suspend fun browserRead(args: JsonObject): JsonElement {
        val max = (args.int("maxChars") ?: 8_000).coerceIn(500, 40_000)
        return textResult(browserPageText(max))
    }

    private suspend fun browserClick(args: JsonObject): JsonElement {
        val id = args.reqInt("id")
        val result = evalJs(
            """(()=>{const e=document.querySelector('[data-solace-id="$id"]');if(!e)return 'missing';e.scrollIntoView({block:'center'});e.click();return 'ok'})()""",
        )
        if (result == "missing") return errorResult("invalid_args", "No element $id; call browser_read.")
        delay(700)
        return textResult("Clicked $id.\n" + browserPageText(12_000))
    }

    private suspend fun browserType(args: JsonObject): JsonElement {
        val id = args.reqInt("id")
        val text = JsonPrimitive(args.reqStr("text")).toString()
        val submit = args.bool("submit") == true
        val result = evalJs(
            """(()=>{const e=document.querySelector('[data-solace-id="$id"]');if(!e)return 'missing';e.focus();
if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('insertText',false,$text);}
else if(!('value' in e))return 'not_editable';
else{const s=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');s&&s.set?s.set.call(e,$text):(e.value=$text);
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
if($submit){e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));if(e.form)e.form.requestSubmit?e.form.requestSubmit():e.form.submit();}
return 'ok'})()""",
        )
        if (result == "missing") return errorResult("invalid_args", "No element $id; call browser_read.")
        if (result == "not_editable") return errorResult("invalid_args", "Element $id is not a text field.")
        delay(700)
        return textResult("Typed.\n" + browserPageText(12_000))
    }

    private suspend fun browserScroll(args: JsonObject): JsonElement {
        val js = when (args.reqStr("direction")) {
            "up" -> "window.scrollBy(0,-window.innerHeight*0.8)"
            "top" -> "window.scrollTo(0,0)"
            "bottom" -> "window.scrollTo(0,document.body.scrollHeight)"
            else -> "window.scrollBy(0,window.innerHeight*0.8)"
        }
        evalJs("(()=>{$js;return 'ok'})()")
        delay(400)
        return textResult(browserPageText(12_000))
    }

    private suspend fun browserBack(): JsonElement {
        val canBack = withContext(Dispatchers.Main) { browserView().canGoBack() }
        if (!canBack) return errorResult("no_history", "There is no previous page.")
        webStartedAt = 0
        webFinishedAt = 0
        withContext(Dispatchers.Main) { browserView().goBack() }
        withTimeoutOrNull(10_000) {
            while (webStartedAt == 0L || webFinishedAt < webStartedAt) delay(100)
        }
        delay(300)
        return textResult(browserPageText(12_000))
    }

    private suspend fun browserScreenshot(): JsonElement = withContext(Dispatchers.Main) {
        val view = browserView()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            bitmap.recycle()
            out.toByteArray()
        }
        imageResult("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private suspend fun browserPageText(maxChars: Int): String {
        val raw = evalJs(BROWSER_READ_JS)
        return raw.take(maxChars)
    }

    private suspend fun evalJs(script: String): String = withContext(Dispatchers.Main) {
        val view = browserView()
        val done = CompletableDeferred<String>()
        view.evaluateJavascript(script) { raw ->
            val decoded = runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonPrimitive)?.content
            }.getOrNull()
            done.complete(decoded ?: raw)
        }
        done.await()
    }

    @Suppress("SetJavaScriptEnabled")
    private suspend fun browserView(): WebView = withContext(Dispatchers.Main) {
        web ?: WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webStartedAt = SystemClock.elapsedRealtime()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    webFinishedAt = SystemClock.elapsedRealtime()
                }
            }
            val m = metrics()
            measure(
                View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, m.widthPixels, m.heightPixels)
        }.also { web = it }
    }

    private fun notificationListener(): SolaceNotificationListener = SolaceNotificationListener.instance
        ?: throw MissingPermission("Notification access. Enable Solace in system notification-listener settings.")

    private suspend fun notificationList(args: JsonObject): JsonElement {
        val listener = notificationListener()
        val packageName = args.str("package")
        val limit = (args.int("limit") ?: 30).coerceIn(1, 100)
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val notifications = listener.activeNotifications.orEmpty()
            .filter { packageName == null || it.packageName == packageName }
            .sortedByDescending { it.postTime }
            .take(limit)
        return textResult(notifications.joinToString("\n") {
            "${format.format(Date(it.postTime))} ${describeNotification(it)}  key=${it.key}"
        }.ifEmpty { "No notifications." })
    }

    private fun notificationDismiss(args: JsonObject): JsonElement {
        val listener = notificationListener()
        val keys = args.strs("keys").toSet()
        val targets = listener.activeNotifications.orEmpty().filter {
            it.isClearable && (keys.isEmpty() || it.key in keys)
        }
        listener.cancelNotifications(targets.map { it.key }.toTypedArray())
        return textResult("Dismissed ${targets.size}.")
    }

    private suspend fun notificationOpen(args: JsonObject): JsonElement {
        val notification = findNotification(args.reqStr("key"))
        val title = args.str("action")
        val pending = if (title == null) {
            notification.notification.contentIntent
        } else {
            notification.notification.actions.orEmpty()
                .firstOrNull { it.title?.toString().equals(title, ignoreCase = true) }
                ?.actionIntent
        } ?: return errorResult("invalid_args", "No matching action on this notification.")
        return try {
            pending.send(app, 0, null, null, null, null, sendOptions())
            if (title == null && notification.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
                runCatching { notificationListener().cancelNotification(notification.key) }
            }
            textResult(if (title == null) "Notification opened." else "Pressed \"$title\".")
        } catch (e: PendingIntent.CanceledException) {
            errorResult("failed", "The app withdrew this notification action.")
        }
    }

    private suspend fun notificationReply(args: JsonObject): JsonElement {
        val notification = findNotification(args.reqStr("key"))
        val text = args.reqStr("text")
        val action = notification.notification.actions.orEmpty().firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return errorResult("unsupported", "This notification has no inline reply action.")
        val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(
            inputs.toTypedArray(),
            fill,
            Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } },
        )
        return try {
            action.actionIntent.send(app, 0, fill, null, null, null, sendOptions())
            textResult("Reply submitted through the inline notification action.")
        } catch (e: PendingIntent.CanceledException) {
            errorResult("failed", "The app withdrew the reply action.")
        }
    }

    private fun findNotification(key: String): StatusBarNotification =
        notificationListener().activeNotifications.orEmpty().firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("No current notification with key $key; call notification_list again.")

    private fun describeNotification(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ""
        val messages = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.messages
        }.getOrNull()?.takeLast(5)?.joinToString(" | ") {
            val person = it.person?.name
            "${person?.takeIf { name -> name.isNotBlank() }?.let { name -> "$name: " }.orEmpty()}${it.text}"
        }
        val text = messages?.takeIf { it.isNotBlank() }
            ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: "").toString()
        val actions = sbn.notification.actions.orEmpty().mapNotNull { action ->
            action.title?.toString()?.let { title ->
                if (action.remoteInputs?.isNotEmpty() == true) "$title(reply)" else title
            }
        }
        return buildString {
            append('[').append(sbn.packageName).append("] ").append(title).append(": ").append(text.take(400))
            if (actions.isNotEmpty()) append("  actions=").append(actions.joinToString("/"))
            if (!sbn.isClearable) append("  (ongoing)")
        }
    }

    private fun sendOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null

    private suspend fun contactsSearch(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.READ_CONTACTS, "Contacts")
        val query = args.reqStr("query")
        val rows = mutableListOf<String>()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        app.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < 50) rows += "${cursor.getString(0)}: ${cursor.getString(1)}"
        }
        textResult(rows.distinct().joinToString("\n").ifEmpty { "No contacts found." })
    }

    private suspend fun smsRead(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.READ_SMS, "SMS")
        val query = args.str("query")
        val limit = (args.int("limit") ?: 20).coerceIn(1, 200)
        val rows = mutableListOf<String>()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        app.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            query?.let { "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?" },
            query?.let { arrayOf("%$it%", "%$it%") },
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val direction = when (cursor.getInt(3)) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "←"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "→"
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "(draft) →"
                    else -> "..."
                }
                rows += "${format.format(Date(cursor.getLong(2)))} $direction ${cursor.getString(0)}: ${cursor.getString(1)}"
            }
        }
        textResult(rows.joinToString("\n").ifEmpty { "No messages." })
    }

    private suspend fun callLogRead(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.READ_CALL_LOG, "Call log")
        val limit = (args.int("limit") ?: 20).coerceIn(1, 200)
        val rows = mutableListOf<String>()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        app.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val type = when (cursor.getInt(3)) {
                    CallLog.Calls.INCOMING_TYPE -> "in"
                    CallLog.Calls.OUTGOING_TYPE -> "out"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "other"
                }
                rows += "${format.format(Date(cursor.getLong(2)))} $type ${cursor.getString(0).orEmpty()} ${cursor.getString(1)} ${cursor.getLong(4)}s"
            }
        }
        textResult(rows.joinToString("\n").ifEmpty { "No calls." })
    }

    private suspend fun calendarRead(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.READ_CALENDAR, "Calendar")
        val from = parseTime(args, "from", "fromMs") ?: System.currentTimeMillis()
        val to = parseTime(args, "to", "toMs") ?: (from + 7L * 86_400_000)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        val rows = mutableListOf<String>()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        app.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
            null,
            null,
            CalendarContract.Instances.BEGIN,
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < 100) {
                rows += "${format.format(Date(cursor.getLong(1)))}–${format.format(Date(cursor.getLong(2)))} " +
                    "${cursor.getString(0)} ${cursor.getString(3).orEmpty()}"
            }
        }
        textResult(rows.joinToString("\n").ifEmpty { "No events." })
    }

    private suspend fun calendarAdd(args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.WRITE_CALENDAR, "Calendar write access")
        val start = parseTime(args, "start", "startMs")
            ?: throw IllegalArgumentException("missing valid 'start' time")
        val end = parseTime(args, "end", "endMs") ?: (start + 3_600_000)
        if (end < start) throw IllegalArgumentException("end is before start")
        val calendar = app.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} " +
                "AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
        } ?: return@withContext errorResult("unsupported", "No writable calendar is configured.")
        val uri = app.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar.first)
                put(CalendarContract.Events.TITLE, args.reqStr("title"))
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                args.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                args.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            },
        ) ?: return@withContext errorResult("failed", "The calendar provider refused the event.")
        textResult("Event created in ${calendar.second} (${calendar.third}); id=${uri.lastPathSegment}.")
    }

    private suspend fun smsSend(args: JsonObject): JsonElement {
        requirePermission(Manifest.permission.SEND_SMS, "Send SMS")
        val to = args.reqStr("to")
        val text = args.reqStr("text")
        @Suppress("DEPRECATION")
        val manager = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = manager.divideMessage(text)
        val action = "${app.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = ConcurrentHashMap<Int, Int>()
        val all = CompletableDeferred<Unit>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results[intent.getIntExtra("part", -1)] = resultCode
                if (results.size >= parts.size) all.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(app, receiver, android.content.IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val intents = parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    app,
                    index,
                    Intent(action).setPackage(app.packageName).putExtra("part", index),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            }
            manager.sendMultipartTextMessage(to, null, parts, ArrayList(intents), null)
            withTimeoutOrNull(30_000) { all.await() }
            val failed = results.values.filter { it != android.app.Activity.RESULT_OK }
            when {
                results.isEmpty() -> textResult("SMS was handed to the phone but no delivery report arrived in 30s.")
                failed.isNotEmpty() -> errorResult("sms_failed", "SMS failed with result code(s): ${failed.distinct().joinToString()}.")
                else -> textResult("SMS sent to $to (${parts.size} part(s)).")
            }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private suspend fun mediaControl(args: JsonObject): JsonElement {
        if (SolaceNotificationListener.instance == null) {
            throw MissingPermission("Notification access (needed for media control).")
        }
        val manager = app.getSystemService(MediaSessionManager::class.java)
        val controller = manager.getActiveSessions(ComponentName(app, SolaceNotificationListener::class.java))
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: manager.getActiveSessions(ComponentName(app, SolaceNotificationListener::class.java)).firstOrNull()
            ?: return textResult("Nothing is playing.")
        val wasPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        when (args.reqStr("action")) {
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "toggle" -> if (wasPlaying) controller.transportControls.pause() else controller.transportControls.play()
            "next" -> controller.transportControls.skipToNext()
            "previous" -> controller.transportControls.skipToPrevious()
            "stop" -> controller.transportControls.stop()
            "status" -> {}
        }
        if (args.str("action") != "status") delay(400)
        val metadata = controller.metadata
        val state = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> "idle"
        }
        return textResult(
            "${controller.packageName}: " +
                "${metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "?"} — " +
                "${metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()} (now $state)",
        )
    }

    private suspend fun clipboardGet(): JsonElement = withContext(Dispatchers.Main) {
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return@withContext errorResult("not_available", "Clipboard is hidden while Solace is in the background.")
        val text = if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(app).toString()
        textResult(text.ifEmpty { "(the clipboard holds empty text)" })
    }

    private suspend fun clipboardSet(args: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Solace personal assistant", args.reqStr("text")))
        textResult("Copied to clipboard.")
    }

    private fun deviceInfo(): JsonElement {
        val battery = app.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val plugged = battery.isCharging
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val networkName = when {
            network == null -> "offline"
            network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val storage = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        val audio = app.getSystemService(AudioManager::class.java)
        val notifications = app.getSystemService(NotificationManager::class.java)
        val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        return textResult(
            buildString {
                appendLine("time: ${ZonedDateTime.now()}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("battery: $level%${if (plugged) ", charging" else ""}")
                appendLine("network: $networkName")
                appendLine("storage: ${"%.1f".format(storage.availableBytes / 1e9)}GB free of ${"%.1f".format(storage.totalBytes / 1e9)}GB")
                appendLine("ringer: ${ringerMode(audio.ringerMode)}")
                appendLine("volume music ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
                appendLine("do-not-disturb: ${notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL}")
                append("locked: ${keyguard.isKeyguardLocked}, screen ${if (power.isInteractive) "on" else "off"}")
            },
        )
    }

    private fun soundMode(args: JsonObject): JsonElement {
        val audio = app.getSystemService(AudioManager::class.java)
        val mode = args.reqStr("mode")
        if (mode == "silent") {
            val notifications = app.getSystemService(NotificationManager::class.java)
            if (!notifications.isNotificationPolicyAccessGranted) {
                throw MissingPermission("Do Not Disturb access.")
            }
        }
        audio.ringerMode = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            else -> throw IllegalArgumentException("mode must be normal, vibrate, or silent")
        }
        return textResult("Ringer mode: $mode.")
    }

    private fun volumeSet(args: JsonObject): JsonElement {
        val audio = app.getSystemService(AudioManager::class.java)
        val stream = when (args.reqStr("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val percent = args.reqInt("percent").coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(stream)
        val value = (max * percent + 50) / 100
        audio.setStreamVolume(stream, value, 0)
        return textResult("Volume set to $percent% ($value/$max).")
    }

    private fun dndSet(args: JsonObject): JsonElement {
        val notifications = app.getSystemService(NotificationManager::class.java)
        if (!notifications.isNotificationPolicyAccessGranted) {
            throw MissingPermission("Do Not Disturb access.")
        }
        val enabled = args.bool("on") == true
        notifications.setInterruptionFilter(
            if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return textResult("Do Not Disturb ${if (enabled) "enabled" else "disabled"}.")
    }

    private fun brightnessSet(args: JsonObject): JsonElement {
        if (!Settings.System.canWrite(app)) throw MissingPermission("Modify system settings.")
        if (args.bool("auto") == true) {
            Settings.System.putInt(
                app.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
            return textResult("Adaptive brightness enabled.")
        }
        val percent = args.int("percent")?.coerceIn(0, 100)
            ?: throw IllegalArgumentException("Give percent or auto.")
        val raw = (Math.pow(percent / 100.0, 2.2) * 255).toInt().coerceIn(1, 255)
        Settings.System.putInt(
            app.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        return textResult("Brightness set to $percent%.")
    }

    private fun flashlight(args: JsonObject): JsonElement {
        val manager = app.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return errorResult("unsupported", "This phone has no camera flash.")
        runCatching { manager.setTorchMode(cameraId, args.bool("on") == true) }
            .getOrElse { return errorResult("failed", it.message ?: "The flashlight is unavailable.") }
        return textResult("Flashlight ${if (args.bool("on") == true) "on" else "off"}.")
    }

    private fun alarmSet(args: JsonObject): JsonElement {
        val timer = args.int("timerSeconds")
        val intent = if (timer != null) {
            if (timer !in 1..86_400) throw IllegalArgumentException("timerSeconds must be 1..86400")
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, timer)
        } else {
            val hour = args.int("hour") ?: throw IllegalArgumentException("Give hour or timerSeconds.")
            val minute = args.int("minute") ?: 0
            if (hour !in 0..23 || minute !in 0..59) throw IllegalArgumentException("Invalid alarm time.")
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        }
        args.str("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            textResult("Alarm/timer request sent to the clock app.")
        } catch (e: Exception) {
            errorResult("failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun settingGet(args: JsonObject): JsonElement {
        val namespace = args.reqStr("namespace")
        val key = args.reqStr("key")
        val value = when (namespace) {
            "system" -> Settings.System.getString(app.contentResolver, key)
            "secure" -> Settings.Secure.getString(app.contentResolver, key)
            "global" -> Settings.Global.getString(app.contentResolver, key)
            else -> throw IllegalArgumentException("namespace must be system, secure, or global")
        }
        if (value != null) return textResult("$namespace $key = $value")
        val result = runShell("settings get ${shellQuote(namespace)} ${shellQuote(key)}")
            ?: return errorResult("not_available", "Unable to run the shell command.")
        return textResult("$namespace $key = ${result.second.trim().ifEmpty { "null" }}")
    }

    private suspend fun settingPut(args: JsonObject): JsonElement {
        val namespace = args.reqStr("namespace")
        if (namespace !in setOf("system", "secure", "global")) {
            throw IllegalArgumentException("namespace must be system, secure, or global")
        }
        val command = "settings put ${shellQuote(namespace)} ${shellQuote(args.reqStr("key"))} ${shellQuote(args.reqStr("value"))}"
        val result = runShell(command) ?: return errorResult("not_available", "Solace's shell is not connected.")
        return if (result.first == 0) textResult("Setting updated.") else errorResult("failed", result.second)
    }

    private suspend fun shellRun(args: JsonObject): JsonElement {
        val result = runShell(args.reqStr("command")) ?: return errorResult("not_available", "Solace's shell is not connected.")
        return buildJsonObject {
            put("ok", result.first == 0)
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", if (result.second.isBlank()) "(no output)" else result.second.take(100_000))
                }
            }
            if (result.first != 0) {
                putJsonObject("error") {
                    put("code", "exit_${result.first}")
                    put("message", result.second.take(2_000))
                }
            }
        }
    }

    private suspend fun runShell(command: String): Pair<Int, String>? {
        val screen = device() as? AndroidScreen ?: return null
        screen.ensureDisplay()
        val result = screen.systemCommand(command) ?: return null
        return result.exitCode to result.output
    }

    private fun ringerMode(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    private fun parseTime(args: JsonObject, isoKey: String, millisKey: String): Long? {
        args.str(isoKey)?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
            val zone = ZoneId.systemDefault()
            runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            runCatching { return ZonedDateTime.parse(raw).toInstant().toEpochMilli() }
            runCatching { return LocalDateTime.parse(raw.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
            runCatching { return LocalDateTime.parse(raw.replace(' ', 'T') + ":00").atZone(zone).toInstant().toEpochMilli() }
            runCatching {
                return LocalDate.parse(raw).atStartOfDay(if (raw.length == 10) ZoneOffset.UTC else zone)
                    .toInstant().toEpochMilli()
            }
            throw IllegalArgumentException("$isoKey is not ISO-8601: $raw")
        }
        return args.long(millisKey)
    }

    private fun requirePermission(permission: String, label: String) {
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw MissingPermission("$label permission is not granted.")
        }
    }

    private fun requireAllFiles() {
        val granted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw MissingPermission("All files access.")
    }

    private fun sharedFile(raw: String): File {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val file = (if (raw.startsWith("/")) File(raw) else File(root, raw)).canonicalFile
        val allowed = listOf(root.path, "/sdcard", "/storage").any {
            file.path == it || file.path.startsWith("$it${File.separator}")
        }
        val blocked = file.path.startsWith(File(root, "Android/data").path) ||
            file.path.startsWith(File(root, "Android/obb").path)
        if (!allowed || blocked) throw IllegalArgumentException("Path outside shared storage is not accessible: ${file.path}")
        return file
    }

    private fun describeFile(file: File): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(file.lastModified()))
        return if (file.isDirectory) "d  ${"-".padStart(9)}  $date  ${file.path}/"
        else "-  ${humanBytes(file.length()).padStart(9)}  $date  ${file.path}"
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1fG".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1fM".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1fK".format(bytes / (1L shl 10).toDouble())
        else -> "${bytes}B"
    }

    private fun simpleGlob(glob: String): Regex {
        val p = StringBuilder("^")
        glob.forEach { ch ->
            when (ch) {
                '*' -> p.append(".*")
                '?' -> p.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> p.append('\\').append(ch)
                else -> p.append(ch)
            }
        }
        return Regex(p.append('$').toString(), RegexOption.IGNORE_CASE)
    }

    private fun imageBytes(file: File): ByteArray? = runCatching {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: return null
        val scale = minOf(1f, 1_600f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        bytes
    }.getOrNull()

    private fun officeText(file: File): String {
        val members = when (file.extension.lowercase()) {
            "docx" -> listOf("word/document.xml")
            "xlsx" -> listOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml")
            "pptx" -> listOf("ppt/slides/slide1.xml")
            else -> emptyList()
        }
        val text = StringBuilder()
        ZipFile(file).use { zip ->
            members.forEach { name ->
                val entry = zip.getEntry(name) ?: return@forEach
                val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                xml.replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { text.appendLine(it) }
            }
        }
        return text.toString().ifBlank { "[${file.extension.uppercase()} contains no extractable text in the first part]" }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun device(): ScreenPort = deviceLock.withLock {
        phone?.let { return it }
        val shared = app.phone.sharedShell.acquire()
        val shell = withTimeoutOrNull(15_000) { shared.shell.filterNotNull().first() }
            ?: run {
                app.phone.sharedShell.release()
                throw IllegalStateException("Solace 的内置无线调试助手尚未连接；请先在“手机”设置中完成配对。")
            }
        val a11y = AccessibilityRepair.awaitService(
            ScreenAccessibility.instance,
            app.phone.recovery.accessibilityRepair.phase,
            5_000,
        ) ?: run {
            app.phone.sharedShell.release()
            throw IllegalStateException("Solace 的无障碍服务未运行；请先在系统设置中启用。")
        }
        app.taskRecovery.beginRun(shell, DEVICE_RUN_KEY)
        val m = metrics()
        val lazyDisplay = LazyAgentDisplay(m.widthPixels, m.heightPixels) {
            AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi)
        }
        val screen = AndroidScreen(
            a11y = a11y,
            shell = shell,
            display = lazyDisplay,
            clipHeld = { app.taskRecovery.markClipHeld(it) },
            clipLock = app.resources.clipboard,
            checkOtherTasks = true,
            reserve = { packageName ->
                app.resources.reserveApp(
                    packageName,
                    DEVICE_RUN_KEY,
                    holderActive = { owner ->
                        owner == DEVICE_RUN_KEY || app.taskState.runs.value.any { it.key == owner }
                    },
                    stillUsed = {
                        runCatching { shell.displayOfPackage(packageName) }.getOrDefault(-1) >= 0
                    },
                )
            },
            clipSaved = { original, ours -> app.taskRecovery.saveClip(original, ours) },
            handedOver = { false },
        )
        binding = shared
        this.display = lazyDisplay
        phone = screen
        screen
    }

    private fun metrics(): DisplayMetrics = DisplayMetrics().also { m ->
        @Suppress("DEPRECATION")
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
    }

    private fun resolveElement(args: JsonObject, snapshot: ScreenSnapshot): UiElement? {
        args.str("node")?.takeIf { it.isNotBlank() }?.let { raw ->
            return snapshot.elements.firstOrNull { it.ref == raw } ?: throw IllegalArgumentException(
                "Unknown or expired element id $raw; call screen_read again.",
            )
        }
        val text = args.str("text")?.takeIf { it.isNotBlank() } ?: return null
        return snapshot.elements.firstOrNull { it.name.equals(text, true) }
            ?: snapshot.elements.firstOrNull { it.name.contains(text, true) }
            ?: throw IllegalArgumentException("No element with text '$text'; call screen_read.")
    }

    private fun centerOf(bounds: List<Int>): Pair<Int, Int> =
        if (bounds.size == 4) (bounds[0] + bounds[2]) / 2 to (bounds[1] + bounds[3]) / 2 else 0 to 0

    private fun actionResult(prefix: String, action: ActionResult): JsonElement = when (action) {
        is ActionResult.Ok -> textResult(
            buildString {
                append(prefix)
                when (action.changed) {
                    true -> append("; the screen changed.")
                    false -> append("; the screen did not change.")
                    null -> {}
                }
                action.hint?.let { append(" ").append(it) }
            },
        )
        is ActionResult.Failed -> errorResult(action.reason, action.hint ?: action.reason)
    }

    private fun descriptorTimeout(name: String): Long =
        SolaceHarnessCapabilityDescriptors.all.firstOrNull { it.name == name }?.timeoutMs ?: 300_000

    private fun callKey(sessionId: String, callId: String) = "$sessionId\u0000$callId"

    private fun textResult(text: String) = buildJsonObject {
        put("ok", true)
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
    }

    private fun imageResult(mime: String, data: String, caption: String? = null) = buildJsonObject {
        put("ok", true)
        putJsonArray("content") {
            caption?.let {
                addJsonObject {
                    put("type", "text")
                    put("text", it)
                }
            }
            addJsonObject {
                put("type", "image")
                put("mimeType", mime)
                put("data", data)
            }
        }
    }

    private fun errorResult(code: String, message: String) = buildJsonObject {
        put("ok", false)
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", message)
            }
        }
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun System.currentTimeMillis() = System.nanoTime() / 1_000_000L

    private fun handoverJson(result: HandoverResult) = buildJsonObject {
        put("ok", true)
        putJsonObject("handover") {
            put("outcome", result.outcome)
            put("inputCount", result.inputCount)
            put("stoppedAt", result.stoppedAt)
            put("skipped", result.skipped)
        }
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put(
                    "text",
                    if (result.skipped) "The user skipped or the handover ended without acting; re-check the screen and continue or stop." +
                        " Outcome: ${result.outcome}."
                    else "The user finished handling it on the phone. Outcome: ${result.outcome}. Read the screen to verify the result.",
                )
            }
        }
    }

    private companion object {
        const val DEVICE_RUN_KEY = "harness-device"

        val BROWSER_READ_JS = """
            (() => {
              document.querySelectorAll('[data-solace-id]').forEach((element) => {
                element.removeAttribute('data-solace-id');
              });
              const output = [];
              const selector = [
                'a[href]',
                'button',
                'input:not([type=hidden])',
                'textarea',
                'select',
                '[role=button]',
                '[role=link]',
                '[onclick]',
                '[contenteditable=true]'
              ].join(',');
              document.querySelectorAll(selector).forEach((element) => {
                const rect = element.getBoundingClientRect();
                if (rect.width < 2 || rect.height < 2) return;
                const label = (
                  element.innerText ||
                  element.value ||
                  element.placeholder ||
                  element.getAttribute('aria-label') ||
                  element.title ||
                  element.alt ||
                  ''
                ).trim().replace(/\s+/g, ' ').slice(0, 80);
                const id = output.length;
                element.setAttribute('data-solace-id', String(id));
                const href = element.href ? ' -> ' + element.href.slice(0, 120) : '';
                output.push('[' + id + '] ' + element.tagName.toLowerCase() + ' "' + label + '"' + href);
              });
              const bodyText = document.body ? document.body.innerText : '';
              const text = bodyText.replace(/\n{3,}/g, '\n\n').slice(0, 30000);
              return 'URL: ' + location.href +
                '\nTitle: ' + document.title +
                '\n\n' + text +
                '\n\n--- interactive ---\n' + output.slice(0, 200).join('\n');
            })()
        """.trimIndent()
    }
}

private class MissingPermission(message: String) : Exception(message)

/** 一次 hand_to_user 的结局:等用户做完交回、用户直接说跳过,或者等待超时。 */
data class HandoverResult(
    val outcome: String,
    val inputCount: Int,
    val stoppedAt: Long,
    val skipped: Boolean,
)

/**
 * hand_to_user 的等待通道,按会话分开。引擎调 hand_to_user 时挂起等用户点通知;
 * TaskRunner 交回时完成对应的 deferred。纯 JVM,方便单测。
 */
class SolaceHarnessHandoverChannel(private val timeoutMs: Long = HANDOVER_TIMEOUT_MS) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<HandoverResult>>()

    fun connect(sessionId: String) {
        pending.remove(sessionId)?.complete(
            HandoverResult(outcome = "skipped", inputCount = 0, stoppedAt = System.currentTimeMillis(), skipped = true),
        )
    }

    suspend fun wait(sessionId: String?, reason: String): HandoverResult {
        val id = sessionId.orEmpty()
        if (id.isBlank()) {
            return HandoverResult(outcome = "skipped", inputCount = 0, stoppedAt = System.currentTimeMillis(), skipped = true)
        }
        val deferred = CompletableDeferred<HandoverResult>()
        pending.put(id, deferred)?.complete(
            HandoverResult(outcome = "skipped", inputCount = 0, stoppedAt = System.currentTimeMillis(), skipped = true),
        )
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id, deferred)
        return result ?: HandoverResult(
            outcome = "handover_timeout",
            inputCount = 0,
            stoppedAt = System.currentTimeMillis(),
            skipped = true,
        )
    }

    /** [outcome] ∈ complete/skipped/finished;未知会话直接忽略,不崩。 */
    fun complete(sessionId: String, outcome: String) {
        pending[sessionId]?.complete(
            HandoverResult(
                outcome = outcome,
                inputCount = 0,
                stoppedAt = System.currentTimeMillis(),
                skipped = outcome != "complete",
            ),
        )
    }

    fun cancel(sessionId: String) {
        pending.remove(sessionId)?.complete(
            HandoverResult(outcome = "skipped", inputCount = 0, stoppedAt = System.currentTimeMillis(), skipped = true),
        )
    }

    fun clear() {
        val stamp = System.currentTimeMillis()
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.complete(HandoverResult(outcome = "skipped", inputCount = 0, stoppedAt = stamp, skipped = true))
        }
    }

    companion object {
        const val HANDOVER_TIMEOUT_MS = 10L * 60 * 1000
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.reqStr(key: String): String = str(key)?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("missing '$key'")
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
    it.intOrNull ?: it.doubleOrNull?.takeIf(Double::isFinite)?.toInt()
}
private fun JsonObject.reqInt(key: String): Int = int(key) ?: throw IllegalArgumentException("missing '$key'")
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let {
    it.longOrNull ?: it.doubleOrNull?.takeIf(Double::isFinite)?.toLong()
}
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
    it.booleanOrNull ?: it.contentOrNull?.lowercase()?.toBooleanStrictOrNull()
}
private fun Boolean?.orFalse() = this == true
private fun JsonObject.strs(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    else -> emptyList()
}

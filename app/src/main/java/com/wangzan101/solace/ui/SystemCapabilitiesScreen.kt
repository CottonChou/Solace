package com.wangzan101.solace.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.solace.settings.VendorKeepAlive
import com.wangzan101.solace.R
import com.wangzan101.solace.settings.SystemCapability
import com.wangzan101.solace.settings.SystemStatus
import com.wangzan101.solace.ui.components.FooterNote
import com.wangzan101.solace.ui.components.GroupCard
import com.wangzan101.solace.ui.components.GroupLabel
import com.wangzan101.solace.ui.components.ListRow
import com.wangzan101.solace.ui.components.PageScaffold
import com.wangzan101.solace.ui.components.RowChevron
import com.wangzan101.solace.ui.components.RowValue
import com.wangzan101.solace.ui.components.StateCard
import com.wangzan101.solace.ui.components.TileTone
import com.wangzan101.solace.ui.components.ValueTone
import com.wangzan101.solace.ui.theme.SolaceIcons

/**
 * 设置 › 系统能力自检。状态由 [SystemStatus] 提供;点一行时交给 Activity
 * 请求运行时权限或打开相应系统授权页,用户返回后现有状态监控会刷新。
 */
@Composable
fun SystemCapabilitiesScreen(
    status: SystemStatus,
    onAction: (SystemCapability) -> Unit,
    onBack: () -> Unit,
) {
    val capabilities = status.capabilities
    val context = LocalContext.current
    // 进入页面读一次;从厂商设置页回来(重新可见)再读,兜底原因也一起清掉或更新
    var keepAlive by remember { mutableStateOf(VendorKeepAlive.readStatus(context)) }
    var keepAliveFallback by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        keepAlive = VendorKeepAlive.readStatus(context)
        onPauseOrDispose { }
    }
    PageScaffold(stringResource(R.string.settings_system_capabilities), onBack) {
        val title = stringResource(
            if (capabilities.allGranted) R.string.system_capabilities_ready
            else R.string.system_capabilities_incomplete,
        )
        StateCard(
            if (capabilities.allGranted) SolaceIcons.Check else SolaceIcons.Shield,
            title,
            stringResource(
                R.string.system_capabilities_progress,
                capabilities.grantedCount,
                SystemCapability.entries.size,
            ),
            if (capabilities.allGranted) TileTone.Ok else TileTone.Warn,
            Modifier.testTag("system-capabilities-status"),
        )

        GroupLabel(stringResource(R.string.settings_system_capabilities_group))
        GroupCard {
            CapabilityRow(
                SystemCapability.NotificationListener,
                SolaceIcons.Bell,
                R.string.system_capability_notification_listener,
                R.string.system_capability_notification_listener_sub,
                status,
                onAction,
                first = true,
            )
            CapabilityRow(
                SystemCapability.AllFiles,
                SolaceIcons.Doc,
                R.string.system_capability_all_files,
                R.string.system_capability_all_files_sub,
                status,
                onAction,
            )
            CapabilityRow(
                SystemCapability.Sms,
                SolaceIcons.Message,
                R.string.system_capability_sms,
                R.string.system_capability_sms_sub,
                status,
                onAction,
            )
            CapabilityRow(
                SystemCapability.Contacts,
                SolaceIcons.Chats,
                R.string.system_capability_contacts,
                R.string.system_capability_contacts_sub,
                status,
                onAction,
            )
            CapabilityRow(
                SystemCapability.Calendar,
                SolaceIcons.Clock,
                R.string.system_capability_calendar,
                R.string.system_capability_calendar_sub,
                status,
                onAction,
            )
            CapabilityRow(
                SystemCapability.WriteSettings,
                SolaceIcons.Wrench,
                R.string.system_capability_write_settings,
                R.string.system_capability_write_settings_sub,
                status,
                onAction,
            )
            CapabilityRow(
                SystemCapability.Dnd,
                SolaceIcons.Mute,
                R.string.system_capability_dnd,
                R.string.system_capability_dnd_sub,
                status,
                onAction,
            )
            CapabilityRow(
                SystemCapability.ExactAlarm,
                SolaceIcons.Alarm,
                R.string.system_capability_exact_alarm,
                R.string.system_capability_exact_alarm_sub,
                status,
                onAction,
            )
        }
        if (keepAlive.manufacturer != null) {
            GroupLabel(stringResource(R.string.vendor_keep_alive_group))
            GroupCard {
                VendorEntryRow(
                    VendorKeepAlive.Entry.Battery,
                    SolaceIcons.Shield,
                    R.string.vendor_keep_alive_battery,
                    R.string.vendor_keep_alive_battery_sub,
                    keepAlive.batteryEntryAvailable,
                    keepAliveFallback,
                    first = true,
                ) { entry ->
                    VendorKeepAlive.tryStart(context, entry) { keepAliveFallback = it }
                }
                VendorEntryRow(
                    VendorKeepAlive.Entry.AutoStart,
                    SolaceIcons.Spark,
                    R.string.vendor_keep_alive_autostart,
                    R.string.vendor_keep_alive_autostart_sub,
                    keepAlive.autoStartEntryAvailable,
                    keepAliveFallback,
                ) { entry ->
                    VendorKeepAlive.tryStart(context, entry) { keepAliveFallback = it }
                }
                VendorEntryRow(
                    VendorKeepAlive.Entry.BackgroundPopup,
                    SolaceIcons.Message,
                    R.string.vendor_keep_alive_popup,
                    R.string.vendor_keep_alive_popup_sub,
                    keepAlive.backgroundPopupEntryAvailable,
                    keepAliveFallback,
                ) { entry ->
                    VendorKeepAlive.tryStart(context, entry) { keepAliveFallback = it }
                }
            }
            FooterNote(stringResource(R.string.vendor_keep_alive_note))
        }
        FooterNote(stringResource(R.string.system_capabilities_footer))
    }
}

@Composable
private fun VendorEntryRow(
    entry: VendorKeepAlive.Entry,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    subtitle: Int,
    available: Boolean,
    fallbackComponent: String?,
    first: Boolean = false,
    onStart: (VendorKeepAlive.Entry) -> Unit,
) {
    val fallbackText = if (!available) fallbackComponent?.let { stringResource(R.string.vendor_keep_alive_fallback_detail, it) } else null
    ListRow(
        icon,
        stringResource(title),
        stringResource(subtitle),
        first = first,
        tone = if (available) TileTone.Neutral else TileTone.Warn,
        detail = fallbackText,
        onClick = { onStart(entry) },
    ) {
        RowValue(
            stringResource(if (available) R.string.vendor_keep_alive_entry else R.string.vendor_keep_alive_fallback),
            if (available) ValueTone.Ok else ValueTone.Bad,
        )
        RowChevron()
    }
}


@Composable
private fun CapabilityRow(
    capability: SystemCapability,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    subtitle: Int,
    status: SystemStatus,
    onAction: (SystemCapability) -> Unit,
    first: Boolean = false,
) {
    val granted = status.capabilities.isGranted(capability)
    ListRow(
        icon,
        stringResource(title),
        stringResource(subtitle),
        first = first,
        tone = if (granted) TileTone.Ok else TileTone.Warn,
        onClick = { onAction(capability) },
    ) {
        RowValue(
            stringResource(if (granted) R.string.system_capability_granted else R.string.system_capability_not_granted),
            if (granted) ValueTone.Ok else ValueTone.Bad,
        )
        RowChevron()
    }
}


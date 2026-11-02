package app.solace.mobile.ui

import org.junit.Rule
import app.solace.mobile.i18n.ChineseTexts
import app.solace.mobile.about.CheckStatus
import app.solace.mobile.about.ReleaseInfo
import app.solace.mobile.about.UpdateState
import app.solace.mobile.core.shell.BuiltinShellStatus
import app.solace.mobile.settings.AccessibilityState
import app.solace.mobile.settings.Prerequisites
import app.solace.mobile.settings.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val r020 = ReleaseInfo("0.2.0", "Solace 0.2.0", "- 新", "2026-11-05", "https://github.com/CottonChou/Solace/releases/tag/v0.2.0", null, null)
    private val r010 = r020.copy(version = "0.1.0")

    @Test
    fun updateShowsDotsEverywhere() {
        val s = UpdateState(latest = r020, update = r020)
        assertTrue(AboutCopy.drawerDot(s))
        assertEquals(AboutCopy.CheckValue("0.2.0 可更新", dot = true, highlight = true), AboutCopy.checkValue(s))
        assertEquals(AboutCopy.CheckValue("0.2.0 可更新", dot = true, highlight = true), AboutCopy.settingsValue(s.update?.version, "0.1.0"))
    }

    @Test
    fun noUpdateNoDots() {
        val s = UpdateState(latest = r010)
        assertFalse(AboutCopy.drawerDot(s))
        assertEquals(AboutCopy.CheckValue(AboutCopy.UP_TO_DATE), AboutCopy.checkValue(s))
        assertEquals(AboutCopy.CheckValue("0.1.0"), AboutCopy.settingsValue(null, "0.1.0"))
        assertFalse(AboutCopy.drawerDot(UpdateState()))
    }

    @Test
    fun checkRowStates() {
        assertEquals(AboutCopy.CheckValue(null), AboutCopy.checkValue(UpdateState()))
        assertTrue(AboutCopy.checkValue(UpdateState(status = CheckStatus.Checking)).spinner)
        assertEquals(AboutCopy.CheckValue(AboutCopy.OFFLINE), AboutCopy.checkValue(UpdateState(status = CheckStatus.Failed)))
        // 连不上但早先查到过新版:照样提示有新版
        assertTrue(AboutCopy.checkValue(UpdateState(latest = r020, update = r020, status = CheckStatus.Failed)).dot)
    }

    @Test
    fun updateSheetSubtitle() {
        assertEquals("当前 0.1.0 Beta · 2026-11-05 发布", AboutCopy.updateSub("0.1.0", beta = true, date = "2026-11-05"))
        assertEquals("当前 0.1.0", AboutCopy.updateSub("0.1.0", beta = false, date = null))
    }

    private fun gap(sdk: Int = 34, shell: BuiltinShellStatus = BuiltinShellStatus.Running,
        a11y: AccessibilityState = AccessibilityState.On, model: Boolean = true, notifications: Boolean = true) =
        AboutCopy.groupTaskGap(Prerequisites.check(SystemStatus(sdk, shell, a11y, notificationsAllowed = notifications), model))

    /** 让 Solace 发消息入群:要操作微信,模型和手机操作都得好;通知关着不挡。 */
    @Test
    fun groupTaskNeedsModelAndPhone() {
        assertNull(gap())
        assertNull(gap(notifications = false))
        assertEquals(AboutCopy.GroupTaskGap.Model, gap(model = false))
        assertEquals(AboutCopy.GroupTaskGap.Model, gap(model = false, shell = BuiltinShellStatus.NotPaired))
        assertEquals(AboutCopy.GroupTaskGap.Phone, gap(shell = BuiltinShellStatus.NotPaired))
        assertEquals(AboutCopy.GroupTaskGap.Phone, gap(a11y = AccessibilityState.Off))
        assertEquals(AboutCopy.GroupTaskGap.TooOld, gap(sdk = 31))
        assertEquals(Fix.AddModel, AboutCopy.GroupTaskGap.Model.fix)
        assertEquals(Fix.OpenPhoneSetup, AboutCopy.GroupTaskGap.Phone.fix)
    }
}

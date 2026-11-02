package app.solace.mobile.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val solace = PermissionCommands.accessibilityService("app.solace.mobile")
    private val solaceLong = "app.solace.mobile/app.solace.mobile.a11y.SolaceAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, solace))
            assertEquals(solace, AccessibilityList.with(raw, solace))
            assertEquals("", AccessibilityList.without(raw, solace))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$solace", AccessibilityList.with(other, solace))
        assertEquals("$other:$other2:$solace", AccessibilityList.with("$other:$other2\n", solace))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$solace", AccessibilityList.with("$other:$solace", solace))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$solaceLong:$other", AccessibilityList.with("$solaceLong:$other", solace))
        assertTrue(AccessibilityList.contains(solaceLong, solace))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$solace:$other2", solace))
        assertEquals(other, AccessibilityList.without("$solaceLong:$other", solace))
        assertEquals(other, AccessibilityList.without(other, solace))
        assertEquals("", AccessibilityList.without(solace, solace))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$solace", AccessibilityList.with(":$other:: $other2 :", solace))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$solace:$other2:", solace))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "app.solace.mobile/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, solace))
        assertEquals(sibling, AccessibilityList.without("$sibling:$solace", solace))
    }
}

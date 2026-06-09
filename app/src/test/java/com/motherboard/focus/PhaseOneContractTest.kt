package com.motherboard.focus

import com.motherboard.focus.storage.InterventionSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhaseOneContractTest {

    @Test
    fun `InterventionSettings has 9 fields including debugLogging`() {
        val fieldNames = InterventionSettings::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("must contain debugLogging", "debugLogging" in fieldNames)
        assertTrue("must contain sessionLimit", "sessionLimit" in fieldNames)
        assertTrue("must contain cooldownDurationMillis", "cooldownDurationMillis" in fieldNames)
        assertTrue("must contain warningDurationMillis", "warningDurationMillis" in fieldNames)
        assertTrue("must contain eventDebounceMillis", "eventDebounceMillis" in fieldNames)
        assertTrue("must contain youtubeShortsEnabled", "youtubeShortsEnabled" in fieldNames)
        assertTrue("must contain blockingEnabled", "blockingEnabled" in fieldNames)
        assertTrue("must contain shortsBlockedToday", "shortsBlockedToday" in fieldNames)
        assertTrue("must contain cooldownsTriggeredToday", "cooldownsTriggeredToday" in fieldNames)
    }

    @Test
    fun `debugLogging defaults to false`() {
        val s = InterventionSettings()
        assertFalse("debugLogging must default to false (privacy-by-default)", s.debugLogging)
    }

    @Test
    fun `debugLogging copy works`() {
        val s = InterventionSettings().copy(debugLogging = true)
        assertTrue("debugLogging copy must work", s.debugLogging)
        assertFalse("original must remain unchanged", InterventionSettings().debugLogging)
    }

    @Test
    fun `all Phase 0 fields still present and default correctly`() {
        val s = InterventionSettings()
        assertEquals(10, s.sessionLimit)
        assertEquals(5 * 60 * 1000L, s.cooldownDurationMillis)
        assertEquals(2000L, s.warningDurationMillis)
        assertEquals(1000L, s.eventDebounceMillis)
        assertTrue(s.youtubeShortsEnabled)
        assertTrue(s.blockingEnabled)
        assertEquals(0, s.shortsBlockedToday)
        assertEquals(0, s.cooldownsTriggeredToday)
    }

    @Test
    fun `accessibility service config XML exists`() {
        val config = File("src/main/res/xml/accessibility_service_config.xml")
        assertTrue("accessibility_service_config.xml must exist", config.exists())
        val content = config.readText()
        assertTrue("must filter for YouTube", content.contains("com.google.android.youtube"))
        assertTrue("must include typeWindowStateChanged", content.contains("typeWindowStateChanged"))
        assertTrue("must include typeWindowContentChanged", content.contains("typeWindowContentChanged"))
        assertFalse("must NOT include typeViewScrolled in Phase 1", content.contains("typeViewScrolled"))
        assertTrue("canRetrieveWindowContent must be false", content.contains("canRetrieveWindowContent=\"false\""))
    }

    @Test
    fun `service class exists in correct package`() {
        val serviceFile = File("src/main/java/com/motherboard/focus/service/MotherboardAccessibilityService.kt")
        assertTrue("MotherboardAccessibilityService.kt must exist", serviceFile.exists())
        val content = serviceFile.readText()
        assertTrue("must extend AccessibilityService", content.contains("AccessibilityService()"))
        assertTrue("must use tag MotherboardA11y", content.contains("MotherboardA11y"))
        assertTrue("must override onAccessibilityEvent", content.contains("override fun onAccessibilityEvent"))
        assertTrue("must override onServiceConnected", content.contains("override fun onServiceConnected"))
        assertTrue("must override onInterrupt", content.contains("override fun onInterrupt"))
        assertTrue("must override onDestroy", content.contains("override fun onDestroy"))
        assertTrue("must have debugLogging companion var", content.contains("@Volatile") && content.contains("debugLogging"))
        assertTrue("must have isRunning StateFlow", content.contains("isRunning"))
    }

    @Test
    fun `no INTERNET permission in manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET must not be declared", manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `strings xml has accessibility description`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("must have accessibility_service_description", strings.contains("accessibility_service_description"))
        assertTrue("must explain why permission is needed", strings.contains("YouTube Shorts"))
        assertTrue("must state no data is sent", strings.contains("does not read messages"))
    }
}

package com.wangzan101.mika.settings

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSettingsTest {
    @Test fun freshDefaultsAreOffWithOneVideoPerTask() {
        val settings = VideoSettings(MemoryPrefs())
        val state = settings.state.value
        assertFalse(state.enabled)
        assertNull(state.choice)
        assertEquals(1, state.maxPerTask)
        // 没选服务商就没有能力,TaskRun 不建生成端口
        assertNull(settings.capabilities())
    }

    @Test fun choiceAndDefaultsSurviveARestart() {
        val prefs = MemoryPrefs()
        val settings = VideoSettings(prefs)
        val choice = VideoChoice("p1", "seedance-model", VideoProtocol.ArkSeedance)
        settings.setChoice(choice)
        settings.setEnabled(true)
        settings.setMaxPerTask(3)
        settings.setDurationSec(8)
        settings.setAspect("9:16")
        settings.setResolution("1080p")

        val restarted = VideoSettings(prefs).state.value
        assertEquals(choice, restarted.choice)
        assertTrue(restarted.enabled)
        assertEquals(3, restarted.maxPerTask)
        assertEquals(8, restarted.durationSec)
        assertEquals("9:16", restarted.aspect)
        assertEquals("1080p", restarted.resolution)
    }

    @Test fun perTaskAllowanceIsCapped() {
        val settings = VideoSettings(MemoryPrefs())
        settings.setMaxPerTask(99)
        assertEquals(VideoSettingsState.MAX_PER_TASK_LIMIT, settings.state.value.maxPerTask)
        settings.setMaxPerTask(0)
        assertEquals(1, settings.state.value.maxPerTask)
    }

    @Test fun badJsonFallsBackToDefaults() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("video_settings", "{not json").apply()
        assertEquals(VideoSettingsState(), VideoSettings(prefs).state.value)
    }

    @Test fun anUnknownProtocolDropsTheChoice() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("video_settings", """{"enabled":true,"providerId":"p1","model":"m","protocol":"later-protocol"}""").apply()
        val state = VideoSettings(prefs).state.value
        assertTrue(state.enabled)
        assertNull(state.choice)
    }

    @Test fun seedanceCapabilitiesStayWithinThePublicRange() {
        val settings = VideoSettings(MemoryPrefs())
        settings.setChoice(VideoChoice("p1", "m", VideoProtocol.ArkSeedance))
        val caps = settings.capabilities()!!
        assertEquals(2..12, caps.durations)
        assertFalse("4k" in caps.resolutions)
        assertTrue(caps.supportsFirstFrame)
    }
}

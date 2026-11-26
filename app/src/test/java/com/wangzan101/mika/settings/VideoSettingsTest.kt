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
        // 没选服务商:核对结果是 None,TaskRun 不建生成端口
        assertEquals(VideoSelection.None, VideoProviders.resolve(state.choice, emptyList()))
    }

    @Test fun choiceAndDefaultsSurviveARestart() {
        val prefs = MemoryPrefs()
        val settings = VideoSettings(prefs)
        val choice = VideoChoice("p1", "seedance-model", "ark-seedance")
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

    @Test fun anUnknownProtocolIsKeptButNeverResolves() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("video_settings", """{"enabled":true,"providerId":"p1","model":"m","protocol":"later-protocol"}""").apply()
        val state = VideoSettings(prefs).state.value
        assertTrue(state.enabled)
        assertEquals(VideoChoice("p1", "m", "later-protocol"), state.choice)
        val ark = Provider("p1", "Ark", ProviderConfig.Kind.OpenAiCompat, "https://ark.cn-beijing.volces.com/api/v3")
        assertEquals(
            VideoSelection.Stale(VideoSelection.Reason.NoLongerCompatible),
            VideoProviders.resolve(state.choice, listOf(ark)),
        )
    }

    @Test fun aChoiceWithoutProviderOrProtocolIsDropped() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("video_settings", """{"enabled":true,"model":"m","protocol":"ark-seedance"}""").apply()
        assertNull(VideoSettings(prefs).state.value.choice)
    }
}

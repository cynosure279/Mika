package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 方舟 Seedance 的声明对照官方文档(创建视频生成任务、Base URL 及鉴权,2026-11 核对)。 */
class ArkSeedanceTest {
    @Test fun servesOnlyTheDocumentedDataPlaneBaseUrl() {
        assertTrue(ArkSeedance.serves("https://ark.cn-beijing.volces.com/api/v3"))
        assertTrue(ArkSeedance.serves(" https://ark.cn-beijing.volces.com/api/v3/ "))
        // Coding Plan 地址、明文 http、改了端口、中转、别家
        assertFalse(ArkSeedance.serves("https://ark.cn-beijing.volces.com/api/coding/v3"))
        assertFalse(ArkSeedance.serves("http://ark.cn-beijing.volces.com/api/v3"))
        assertFalse(ArkSeedance.serves("https://ark.cn-beijing.volces.com:8443/api/v3"))
        assertFalse(ArkSeedance.serves("https://relay.example.com/api/v3"))
        assertFalse(ArkSeedance.serves("https://ark.cn-beijing.volces.com.evil.example/api/v3"))
        assertFalse(ArkSeedance.serves("https://api.deepseek.com"))
        assertFalse(ArkSeedance.serves("not a url"))
    }

    @Test fun registryFindsTheBackendByAddressAndId() {
        assertSame(ArkSeedance, VideoBackends.forBaseUrl("https://ark.cn-beijing.volces.com/api/v3"))
        assertNull(VideoBackends.forBaseUrl("https://api.openai.com/v1"))
        assertSame(ArkSeedance, VideoBackends.of("ark-seedance"))
        assertNull(VideoBackends.of("later-protocol"))
    }

    @Test fun seedance25RangesFollowTheDocs() {
        val caps = ArkSeedance.capabilities("doubao-seedance-2-5-260628")
        assertEquals(4..30, caps.durations)
        assertEquals(setOf("480p", "720p", "1080p", "4k"), caps.resolutions)
        assertEquals(setOf("16:9", "4:3", "1:1", "3:4", "9:16", "21:9"), caps.aspects)
        assertTrue(caps.firstFrameKeepsAspect)
        assertTrue(caps.supportsAudio)
    }

    @Test fun seedance25FlashStopsAt720p() {
        val caps = ArkSeedance.capabilities("doubao-seedance-2-5-flash-260628")
        assertEquals(4..30, caps.durations)
        assertEquals(setOf("480p", "720p"), caps.resolutions)
    }

    @Test fun seedance20RangesFollowTheDocs() {
        val caps = ArkSeedance.capabilities("doubao-seedance-2-0-260128")
        assertEquals(4..15, caps.durations)
        assertEquals(setOf("480p", "720p", "1080p", "4k"), caps.resolutions)
        assertFalse(caps.firstFrameKeepsAspect)
        assertEquals(setOf("480p", "720p"), ArkSeedance.capabilities("doubao-seedance-2-0-fast-260128").resolutions)
        assertEquals(setOf("480p", "720p"), ArkSeedance.capabilities("Doubao-Seedance-2-0-Mini").resolutions)
    }

    @Test fun unrecognisedModelsGetWhatEveryDocumentedModelAccepts() {
        val caps = ArkSeedance.capabilities("ep-20261126123456-abcde")
        assertEquals(4..15, caps.durations)
        assertEquals(setOf("480p", "720p"), caps.resolutions)
        assertTrue(caps.firstFrameKeepsAspect)
        assertFalse(caps.supportsAudio)
    }
}

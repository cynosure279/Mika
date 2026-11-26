package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ArkSeedance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSelectionTest {
    private val ark = Provider("ark", "火山方舟", ProviderConfig.Kind.OpenAiCompat, "https://ark.cn-beijing.volces.com/api/v3")
    private val deepseek = Provider("ds", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com")
    private val relay = Provider("relay", "Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.example.com/api/v3")
    private val arkCodingPlan = Provider("ark-cp", "Ark Coding Plan", ProviderConfig.Kind.OpenAiCompat, "https://ark.cn-beijing.volces.com/api/coding/v3")
    private val arkHttp = Provider("ark-http", "Ark http", ProviderConfig.Kind.OpenAiCompat, "http://ark.cn-beijing.volces.com/api/v3")

    @Test fun onlyProvidersThatDeclareVideoGenerationAreOffered() {
        val offered = VideoProviders.compatible(listOf(deepseek, ark, relay, arkCodingPlan, arkHttp))
        assertEquals(listOf("ark"), offered.map { it.provider.id })
        assertEquals(ArkSeedance, offered.single().backend)
    }

    @Test fun theArkPresetIsNamedWhenNoServiceCanGenerate() {
        assertTrue(VideoProviders.presetLabels().contains("火山方舟"))
    }

    @Test fun aCompatibleChoiceResolvesWithTheModelsDeclaredCapabilities() {
        val selection = VideoProviders.resolve(VideoChoice("ark", " doubao-seedance-2-5-260628 ", ArkSeedance.id), listOf(ark))
        selection as VideoSelection.Ready
        assertEquals("doubao-seedance-2-5-260628", selection.model)
        assertEquals(4..30, selection.capabilities.durations)
        assertTrue("4k" in selection.capabilities.resolutions)
    }

    @Test fun aChoiceWithoutAModelIsNotReady() {
        val selection = VideoProviders.resolve(VideoChoice("ark", "  ", ArkSeedance.id), listOf(ark))
        assertEquals(VideoSelection.NoModel(VideoProvider(ark, ArkSeedance)), selection)
    }

    @Test fun aRemovedProviderMakesTheChoiceStale() {
        assertEquals(
            VideoSelection.Stale(VideoSelection.Reason.ProviderRemoved),
            VideoProviders.resolve(VideoChoice("gone", "m", ArkSeedance.id), listOf(ark)),
        )
    }

    @Test fun aProviderWhoseAddressChangedIsNoLongerUsed() {
        // 同一个服务商 ID,地址改成了中转:不能再把 Seedance 请求发过去
        val moved = ark.copy(baseUrl = "https://relay.example.com/api/v3")
        assertEquals(
            VideoSelection.Stale(VideoSelection.Reason.NoLongerCompatible),
            VideoProviders.resolve(VideoChoice("ark", "doubao-seedance-2-0-260128", ArkSeedance.id), listOf(moved)),
        )
    }

    @Test fun aChoiceSavedForANonVideoProviderIsStale() {
        // 旧版设置页列出所有服务商,可能存下了不能生成视频的那家
        assertEquals(
            VideoSelection.Stale(VideoSelection.Reason.NoLongerCompatible),
            VideoProviders.resolve(VideoChoice("ds", "m", ArkSeedance.id), listOf(deepseek)),
        )
    }
}

package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.VideoChoice
import com.wangzan101.mika.settings.VideoProtocol
import com.wangzan101.mika.settings.VideoSettings
import com.wangzan101.mika.settings.VideoSettingsState
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * 设置 › 模型 › 视频模型:选服务商、模型、默认时长/画幅/清晰度、每任务最多几个;打开后才提供 video_generate。
 * 和语音转写、声音模型一样不在 [com.wangzan101.mika.core.model.ModelRole] 里。
 */
@Composable
fun VideoModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VideoSettings.get(context) }
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val c = MikaTheme.colors
    var model by remember { mutableStateOf(state.choice?.model.orEmpty()) }
    var duration by remember { mutableStateOf(state.durationSec.toString()) }
    var maxPerTask by remember { mutableStateOf(state.maxPerTask.toString()) }

    Page(tr(R.string.role_video), onBack) {
        Text(tr(R.string.video_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        GroupCard {
            ListRow(
                null, tr(R.string.video_enable), tr(R.string.video_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        GroupLabel(tr(R.string.video_provider), Modifier.padding(top = 12.dp))
        if (providers.isEmpty()) {
            FooterNote(tr(R.string.video_no_providers))
        } else {
            GroupCard {
                providers.forEachIndexed { i, p ->
                    RadioRow(
                        ModelsCopy.providerName(p), selected = state.choice?.providerId == p.id, first = i == 0,
                        subtitle = p.baseUrl,
                        onClick = { settings.setChoice(VideoChoice(p.id, model, VideoProtocol.ArkSeedance)) },
                    )
                }
            }
        }

        state.choice?.let { choice ->
            GroupCard {
                InputRow(
                    tr(R.string.video_model_name), model,
                    onChange = { model = it; settings.setChoice(choice.copy(model = it)) },
                    placeholder = "doubao-seedance-1-0-pro-250528", first = true,
                )
                InputRow(
                    tr(R.string.video_duration), duration,
                    onChange = {
                        duration = it
                        it.toIntOrNull()?.let { v -> settings.setDurationSec(v.coerceIn(1, 60)) }
                    },
                    placeholder = "5", keyboard = KeyboardType.Number,
                )
                InputRow(
                    tr(R.string.video_max_per_task), maxPerTask,
                    onChange = {
                        maxPerTask = it
                        it.toIntOrNull()?.let { v -> settings.setMaxPerTask(v.coerceIn(1, VideoSettingsState.MAX_PER_TASK_LIMIT)) }
                    },
                    placeholder = "1", keyboard = KeyboardType.Number,
                )
            }

            GroupLabel(tr(R.string.video_aspect))
            GroupCard {
                listOf("16:9", "9:16", "1:1").forEachIndexed { i, aspect ->
                    RadioRow(aspect, state.aspect == aspect, { settings.setAspect(aspect) }, first = i == 0)
                }
            }
            GroupLabel(tr(R.string.video_resolution))
            GroupCard {
                listOf("480p", "720p", "1080p").forEachIndexed { i, resolution ->
                    RadioRow(resolution, state.resolution == resolution, { settings.setResolution(resolution) }, first = i == 0)
                }
            }
        }

        FooterNote(tr(R.string.video_footer), Modifier.padding(top = 10.dp))
    }
}

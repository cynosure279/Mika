package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.plugin.PluginErrors
import com.wangzan101.mika.plugin.PluginInstallException
import com.wangzan101.mika.plugin.PluginInstaller
import com.wangzan101.mika.plugin.PluginManifest
import com.wangzan101.mika.plugin.PluginRecord
import com.wangzan101.mika.plugin.PluginRegistry
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置 › 插件:安装 zip、启停、卸载,只看详情(技能/权限),不提供工具和页面入口。 */
@Composable
fun PluginsScreen(registry: PluginRegistry, onBack: () -> Unit) {
    val plugins by registry.plugins.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { PluginInstaller(context.filesDir) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                installer.install(uri, context.contentResolver)
            }
            result.onSuccess { registry.install(it) }.onFailure { failure = it }
            installing = false
        }
    }

    val selected = selectedId?.let { id -> plugins.firstOrNull { it.id == id } }
    if (selected != null) {
        BackHandler { selectedId = null }
        PluginDetail(
            record = selected,
            registry = registry,
            onBack = { selectedId = null },
            onRemoved = { selectedId = null },
        )
    } else {
        PageScaffold(stringResource(R.string.plugins_title), onBack) {
            GroupCard {
                ListRow(
                    MikaIcons.Plus,
                    if (installing) stringResource(R.string.plugins_installing) else stringResource(R.string.plugins_install),
                    first = true,
                    onClick = if (installing) null else ({ launcher.launch(arrayOf("application/zip")) }),
                ) { if (!installing) RowChevron() }
            }
            if (plugins.isEmpty()) {
                FooterNote(stringResource(R.string.plugins_empty) + "\n\n" + stringResource(R.string.plugins_empty_sub))
            } else {
                GroupLabel(stringResource(R.string.plugins_title))
                GroupCard {
                    plugins.forEachIndexed { i, record ->
                        ListRow(
                            MikaIcons.Spark,
                            record.name,
                            subtitle = "${stringResource(R.string.plugins_version, record.version)} · " +
                                trPlural(R.plurals.plugins_skill_count, record.skillNames.size),
                            first = i == 0,
                            onClick = { selectedId = record.id },
                        ) {
                            MikaSwitch(record.enabled, null)
                            RowChevron()
                        }
                    }
                }
            }
        }
    }

    failure?.let { e ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.plugins_title)) },
            text = { Text(installErrorText(e)) },
            confirmButton = { TextButton({ failure = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun PluginDetail(record: PluginRecord, registry: PluginRegistry, onBack: () -> Unit, onRemoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmUninstall by remember { mutableStateOf(false) }
    val manifest = remember(record.id, record.version) { registry.manifest(record.id) }
    val page = manifest?.pages?.firstOrNull()?.takeIf { "page" in manifest.permissions }
    val permissionLabels = record.permissions.map { permissionLabel(it) }
    PageScaffold(record.name, onBack) {
        GroupCard {
            ListRow(
                null,
                stringResource(R.string.plugins_version, record.version),
                subtitle = record.description?.takeIf { it.isNotBlank() },
                first = true,
            )
            ListRow(null, stringResource(R.string.plugins_enabled)) {
                MikaSwitch(record.enabled, { on ->
                    scope.launch(Dispatchers.IO) { registry.setEnabled(record.id, on) }
                }, Modifier.testTag("plugin-enabled"))
            }
            ListRow(
                null,
                stringResource(R.string.plugins_permissions),
                subtitle = if (record.permissions.isEmpty()) stringResource(R.string.plugins_none)
                else permissionLabels.joinToString(),
            )
        }
        GroupLabel(stringResource(R.string.plugins_tools))
        GroupCard {
            val tools = manifest?.tools.orEmpty()
            if (tools.isEmpty()) {
                ListRow(null, stringResource(R.string.plugins_no_tools), first = true)
            } else {
                tools.forEachIndexed { i, tool ->
                    ListRow(
                        MikaIcons.Terminal,
                        tool.name,
                        subtitle = listOfNotNull(
                            tool.description.takeIf { it.isNotBlank() },
                            stringResource(R.string.plugins_tool_meta, riskLabel(tool), tool.timeoutSec),
                        ).joinToString(" · "),
                        first = i == 0,
                    )
                }
            }
        }
        GroupLabel(stringResource(R.string.plugins_skills))
        GroupCard {
            if (record.skillNames.isEmpty()) {
                ListRow(null, stringResource(R.string.plugins_no_skills), first = true)
            } else {
                record.skillNames.forEachIndexed { i, name ->
                    ListRow(MikaIcons.Doc, name, first = i == 0)
                }
            }
        }
        GroupCard {
            ListRow(
                MikaIcons.Trash,
                stringResource(R.string.plugins_uninstall),
                titleColor = MikaTheme.colors.bad,
                first = true,
                onClick = { confirmUninstall = true },
            )
        }
        page?.let {
            GroupCard {
                ListRow(
                    MikaIcons.Screens,
                    stringResource(R.string.plugins_open_page),
                    subtitle = it.title?.takeIf { title -> title.isNotBlank() },
                    first = true,
                    onClick = { context.startActivity(com.wangzan101.mika.plugin.PluginPageActivity.intent(context, record.id, it.id)) },
                ) { RowChevron() }
            }
        }
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.plugins_uninstall)) },
            text = { Text(stringResource(R.string.plugins_uninstall_body)) },
            confirmButton = {
                TextButton({
                    confirmUninstall = false
                    scope.launch {
                        withContext(Dispatchers.IO) { registry.uninstall(record.id) }
                        onRemoved()
                    }
                }) { Text(stringResource(R.string.plugins_uninstall)) }
            },
            dismissButton = { TextButton({ confirmUninstall = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun permissionLabel(code: String): String = when (code) {
    "sandbox.run" -> stringResource(R.string.plugins_permission_sandbox_run)
    "workspace.own" -> stringResource(R.string.plugins_permission_workspace_own)
    "network" -> stringResource(R.string.plugins_permission_network)
    else -> code
}

@Composable
private fun riskLabel(tool: com.wangzan101.mika.plugin.PluginToolDecl): String =
    if (tool.annotations["destructiveHint"] == true || tool.annotations["openWorldHint"] == true) {
        stringResource(R.string.plugins_risk_external)
    } else {
        stringResource(R.string.plugins_risk_write)
    }

@Composable
private fun installErrorText(e: Throwable): String {
    val code = (e as? PluginInstallException)?.code
    return when (code) {
        PluginManifest.ERR_MIN_APP_TOO_HIGH -> stringResource(R.string.plugins_error_too_new)
        PluginManifest.ERR_PERMISSIONS, PluginManifest.ERR_TOOLS_NEED_SANDBOX,
        PluginManifest.ERR_SETTING_SECRET -> stringResource(R.string.plugins_unsupported_in_version)
        PluginManifest.ERR_PAGES_NEED_PAGE, PluginManifest.ERR_PAGE, PluginManifest.ERR_PAGE_PATH,
        PluginManifest.ERR_SETTINGS_NEED_PERMISSION, PluginManifest.ERR_SETTING,
        PluginManifest.ERR_TOOL, PluginManifest.ERR_TOOL_SCRIPT, PluginManifest.ERR_TOOL_TIMEOUT,
        PluginManifest.ERR_TOOL_SCHEMA, PluginManifest.ERR_TOOL_COST, PluginManifest.ERR_SKILL,
        PluginManifest.ERR_SKILL_PATH, PluginManifest.ERR_ID, PluginManifest.ERR_NAME,
        PluginManifest.ERR_VERSION, PluginManifest.ERR_SCHEMA,
        com.wangzan101.mika.plugin.PluginScriptErrors.CHANGED,
        com.wangzan101.mika.plugin.PluginScriptErrors.SYNC -> stringResource(R.string.plugins_error_invalid)
        PluginErrors.UNSAFE_PATH, com.wangzan101.mika.plugin.PluginScriptErrors.UNSAFE ->
            stringResource(R.string.plugins_error_unsafe)
        PluginErrors.TOO_LARGE -> stringResource(R.string.plugins_error_too_large)
        PluginErrors.MISSING_MANIFEST -> stringResource(R.string.plugins_error_missing_manifest)
        PluginErrors.INVALID_MANIFEST -> stringResource(R.string.plugins_error_invalid)
        else -> stringResource(R.string.plugins_install_failed, e.message ?: code ?: e.javaClass.simpleName)
    }
}

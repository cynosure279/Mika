package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** 对话页的附件:「＋」的回调、相册页、输入框上方待发的那一排、发出去的消息怎么画。 */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentsTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 假的图:每张都是一块 40×30 的空图,不读手机。 */
    private val images = AttachImages { _, _ -> ImageBitmap(40, 30) }

    private fun composer(
        attachments: List<PendingAttachment> = emptyList(),
        text: String = "",
        mode: ComposerMode = ComposerMode.Idle,
        rejected: List<AttachmentFailure> = emptyList(),
        onSend: () -> Unit = {},
        onPlus: (() -> Unit)? = null,
        attachBlocked: Boolean = false,
    ) = compose.setContent {
        MikaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                var t by remember { mutableStateOf(text) }
                Box(Modifier.fillMaxSize()) {
                    Composer(
                        Modifier, t, { t = it }, mode, hasHistory = false, onSend = onSend, onStop = {},
                        onPlus = onPlus,
                        attachBlocked = attachBlocked,
                        attachments = attachments,
                        rejected = rejected,
                    )
                }
            }
        }
    }

    @Test fun rejectedAttachmentsShowTheirIndividualReasons() {
        composer(rejected = listOf(
            AttachmentFailure("big.zip", AttachmentFailureReason.TOO_LARGE),
            AttachmentFailure("photo.jpg", AttachmentFailureReason.LOW_DEVICE_SPACE),
            AttachmentFailure("notes.txt", AttachmentFailureReason.IO_ERROR),
        ))
        compose.onNodeWithText("big.zip：超过", substring = true).assertIsDisplayed()
        compose.onNodeWithText("photo.jpg：手机存储空间不足", substring = true).assertIsDisplayed()
        compose.onNodeWithText("notes.txt：无法读取或复制", substring = true).assertIsDisplayed()
    }

    @Test fun plusOpensTheSheetThroughCallback() {
        var opened = 0
        composer(onPlus = { opened++ })
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        assertEquals(1, opened)
    }

    @Test fun waitingForAnAnswerCanStillOpenTheSheet() {
        var opened = 0
        composer(mode = ComposerMode.Answer, onPlus = { opened++ })
        compose.onNodeWithContentDescription(AttachCopy.ADD).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test fun blockedAttachmentsKeepThePlusButOnlyExplain() {
        var opened = 0
        composer(onPlus = { opened++ }, attachBlocked = true)
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        assertEquals(0, opened)
    }

    @Test fun noPlusWhenAttachmentsAreOff() {
        composer()
        compose.onAllNodesWithContentDescription(AttachCopy.ADD).assertCountEquals(0)
    }

    @Test fun trayShowsPhotosAndFileCardsAndAttachmentsAloneCanBeSent() {
        var sent = 0
        composer(
            listOf(
                PendingAttachment("content://m/1", "a.jpg", 1000),
                PendingAttachment("content://m/2", "b.png", 1000),
                PendingAttachment("content://f/1", "租房合同.pdf", 1200 * 1024),
            ),
            onSend = { sent++ },
        )
        compose.onAllNodesWithTag("tray-photo").assertCountEquals(2)
        compose.onAllNodesWithTag("tray-file").assertCountEquals(1)
        compose.onNodeWithText("租房合同.pdf").assertIsDisplayed()
        compose.onNodeWithText("1.2 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription(AttachCopy.remove("a.jpg")).assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun sendIsOffWithNothingToSend() {
        composer()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun removingFromTheTrayCallsBack() {
        val removed = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PendingTray(listOf(PendingAttachment("content://f/9", "a.pdf", 10)), { removed += it })
                }
            }
        }
        compose.onNodeWithContentDescription(AttachCopy.remove("a.pdf")).performClick()
        assertEquals(listOf("content://f/9"), removed)
    }

    @Test fun clickingLocalTrayItemsOpensThem() {
        val opened = mutableListOf<String>()
        val removed = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PendingTray(
                        items = listOf(
                            PendingAttachment("content://m/1", "a.jpg", 1000),
                            PendingAttachment("content://f/9", "a.pdf", 10),
                        ),
                        onRemove = { removed += it },
                        onOpen = { opened += it.uri },
                    )
                }
            }
        }
        compose.onNodeWithTag("tray-photo").assertHasClickAction().performClick()
        compose.onNodeWithTag("tray-file").assertHasClickAction().performClick()
        assertEquals(listOf("content://m/1", "content://f/9"), opened)
        assertEquals(emptyList<String>(), removed)
    }

    @Test fun localPendingAttachmentOpensInFileViewerAndMissingReturnsFalse() {
        val context = compose.activity
        assertFalse(runBlocking { AttachFiles.openPending(context, "content://nonexistent.invalid/missing.txt", "missing.txt") })
        val dir = File(context.cacheDir, "attach/viewing").apply { mkdirs() }
        val file = File(dir, "pending-preview.txt").apply { writeText("pending preview body") }
        try {
            val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file).toString()
            compose.setContent {
                MikaTheme {
                    CompositionLocalProvider(LocalAttachImages provides images) {
                        PendingTray(listOf(PendingAttachment(uri, "pending-preview.txt", file.length())), onRemove = {})
                    }
                }
            }
            compose.onNodeWithTag("tray-file").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("pending preview body", substring = true).assertIsDisplayed()
            Espresso.pressBack()
        } finally {
            file.delete()
        }
    }

    @Test fun externalUriPendingAttachmentCopiesToViewingAndOpensInFileViewer() {
        val context = compose.activity
        val file = File(context.cacheDir, "external-pending.txt").apply { writeText("copied external body") }
        try {
            val uri = Uri.fromFile(file).toString()
            compose.setContent {
                MikaTheme {
                    CompositionLocalProvider(LocalAttachImages provides images) {
                        PendingTray(listOf(PendingAttachment(uri, "external-pending.txt", file.length())), onRemove = {})
                    }
                }
            }
            compose.onNodeWithTag("tray-file").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("copied external body", substring = true).assertIsDisplayed()
            Espresso.pressBack()
        } finally {
            file.delete()
        }
    }

    private fun photosPage(access: PhotoAccess, recent: List<PendingAttachment>?, selected: List<PendingAttachment> = emptyList(), onGrant: () -> Unit = {}) =
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PlusPhotosPage(access, recent, selected, {}, onGrant, {}, {})
                }
            }
        }

    @Test fun photosPageWithoutPhotoAccessExplainsAndOffersToAllow() {
        var granted = 0
        photosPage(PhotoAccess.None, emptyList(), onGrant = { granted++ })
        compose.onNodeWithText(AttachCopy.NEED_ACCESS).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.GRANT).performClick()
        assertEquals(1, granted)
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(0)
    }

    @Test fun photosPageListsRecentPhotosWithNumberedSelection() {
        val recent = (1..6).map { PendingAttachment("content://m/$it", "p$it.jpg", 1, photo = true) }
        photosPage(PhotoAccess.Full, recent, selected = listOf(recent[4], recent[1]))
        compose.onNodeWithText(AttachCopy.RECENT).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.ALL).assertIsDisplayed()
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(6)
        compose.onAllNodes(hasTestTag("recent-photo") and isSelected()).assertCountEquals(2)
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun partialAccessOffersToChangeTheSelection() {
        photosPage(PhotoAccess.Partial, listOf(PendingAttachment("content://m/1", "p.jpg", 1, photo = true)))
        compose.onNodeWithText(AttachCopy.RECENT_PARTIAL).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.CHANGE).assertIsDisplayed()
    }

    /** 照片条:几张假照片加末尾的「更多」。[fontScale] 叠在系统字号上,[width] 是窄屏,[extra] 画在照片条下面。 */
    private fun recentRow(
        recent: List<PendingAttachment> = (1..4).map { PendingAttachment("content://m/$it", "p$it.jpg", 1, photo = true) },
        selected: List<PendingAttachment> = emptyList(),
        onToggle: (PendingAttachment) -> Unit = {},
        onPreview: ((PendingAttachment) -> Unit)? = null,
        onMore: () -> Unit = {},
        fontScale: Float = 1f,
        width: Dp = 320.dp,
        extra: @Composable () -> Unit = {},
    ) = compose.setContent {
        val density = LocalDensity.current
        MikaTheme {
            CompositionLocalProvider(
                LocalAttachImages provides images,
                LocalDensity provides Density(density.density, density.fontScale * fontScale),
            ) {
                Column {
                    Box(Modifier.width(width)) { PlusRecentRow(recent, selected, onToggle, onMore, onPreview = onPreview) }
                    extra()
                }
            }
        }
    }

    /** 一张 40×30 的真 PNG,给查看页解码。 */
    private fun png(): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    /** 一张放在 FileProvider 里的真照片,查看页能读。 */
    private fun viewablePhoto(): Pair<File, PendingAttachment> {
        val context = compose.activity
        val file = File(context.cacheDir, "attach/viewing/recent-preview.png").apply { parentFile?.mkdirs(); writeBytes(png()) }
        val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file).toString()
        return file to PendingAttachment(uri, "recent-preview.png", file.length(), photo = true)
    }

    /** 等某个标签出现。查看页刚起步、两个界面都还没注册的一瞬间查不到任何界面,这时接着等。 */
    private fun waitForKey(tag: String) = compose.waitUntil(5_000) {
        try {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            false
        }
    }

    @Test fun moreTileKeepsItsLabelOnOneLineWithLargeFonts() {
        // 320dp 的窄屏加 1.3 倍字号:一句四个字的标签在这里放不下,修复前折成「全部照/片」
        recentRow(fontScale = 1.3f) {
            // 同样的字不限宽度再画一遍,用来比宽度:没被省略时两处一样宽
            Text(AttachCopy.MORE, Modifier.testTag("unconstrained-label"), style = MikaType.Caption)
        }
        val label = compose.onNodeWithTag("plus-more-photos-label", useUnmergedTree = true)
        val layout = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals(1, layout.single().lineCount)
        val inTile = label.getBoundsInRoot()
        val full = compose.onNodeWithTag("unconstrained-label", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("label was cut down: $inTile vs $full", inTile.right - inTile.left >= full.right - full.left - 1.dp)
        assertTrue("label was clipped: $inTile vs $full", inTile.bottom - inTile.top >= full.bottom - full.top - 1.dp)
        val tile = compose.onNodeWithTag("plus-more-photos").getBoundsInRoot()
        assertTrue("label leaves its tile: $inTile vs $tile", inTile.left >= tile.left && inTile.right <= tile.right && inTile.top >= tile.top && inTile.bottom <= tile.bottom)
    }

    @Test fun moreTileOpensTheRecentPhotosPage() {
        var opened = 0
        recentRow(onMore = { opened++ })
        compose.onNodeWithTag("plus-more-photos").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }

    @Test fun longPressPreviewsAResentPhotoWithoutChangingTheSelection() {
        val recent = (1..4).map { PendingAttachment("content://m/$it", "p$it.jpg", 1, photo = true) }
        val toggled = mutableListOf<String>()
        val previewed = mutableListOf<String>()
        recentRow(recent, selected = listOf(recent[0]), onToggle = { toggled += it.uri }, onPreview = { previewed += it.uri })
        val cells = compose.onAllNodesWithTag("recent-photo")
        // 长按已选中的第 1 张:只预览,还是选中的、序号不变
        cells[0].performTouchInput { longClick() }
        assertEquals(listOf("content://m/1"), previewed)
        cells[0].assertIsSelected()
        compose.onNodeWithText("1").assertIsDisplayed()
        // 长按没选的第 3 张:只预览,不会被选上
        cells[2].performTouchInput { longClick() }
        assertEquals(listOf("content://m/1", "content://m/3"), previewed)
        compose.onAllNodes(hasTestTag("recent-photo") and isSelected()).assertCountEquals(1)
        assertEquals(emptyList<String>(), toggled)
        // 点一下仍然是选上或取消,不预览
        cells[1].performClick()
        assertEquals(listOf("content://m/2"), toggled)
        assertEquals(2, previewed.size)
    }

    @Test fun longPressOpensTheFullSizeViewerFromTheStrip() {
        val (file, photo) = viewablePhoto()
        try {
            recentRow(listOf(photo))
            compose.onAllNodesWithTag("recent-photo")[0].performTouchInput { longClick() }
            waitForKey("viewer.image")
            compose.onNodeWithTag("viewer.image").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test fun longPressOpensTheFullSizeViewerFromTheGrid() {
        val (file, photo) = viewablePhoto()
        try {
            photosPage(PhotoAccess.Full, listOf(photo))
            compose.onAllNodesWithTag("recent-photo")[0].performTouchInput { longClick() }
            waitForKey("viewer.image")
            compose.onNodeWithTag("viewer.image").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test fun plusSheetGroupsIntoAttachTurnAndReuse() {
        // 避免「首次打开弹照片权限」打断断言
        RecentPhotos.markAsked(compose.activity)
        compose.setContent {
            MikaTheme {
                ChatPlusSheet(
                    selected = emptyList(),
                    onDismiss = {},
                    onPicked = {},
                    onToggle = { false },
                    onFull = {},
                    onDeviceFiles = null,
                    onPaste = {},
                    onTeach = {},
                    onChecklists = {},
                    onWorkflows = {},
                    tasks = emptyList(),
                    onPickTask = {},
                    onPickSkill = {},
                )
            }
        }
        // 第一排四个按钮,中间教 Mika 和技能,下面一行「常用」
        listOf("plus-camera", "plus-album", "plus-files", "plus-clipboard", "plus-teach", "plus-skills")
            .forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithText("常用").assertExists()
        // 浏览器、清单、流程不再直接列在面板上
        compose.onAllNodesWithText("浏览器").assertCountEquals(0)
        compose.onAllNodesWithText("已存清单").assertCountEquals(0)
        // 「常用」里是常用说法,再往下是已存清单和流程
        compose.onNodeWithText("常用").performClick()
        compose.onNodeWithText("常用说法").assertExists()
        compose.onNodeWithText("已存清单").assertExists()
        compose.onNodeWithText("流程").assertExists()
    }

    private fun bubble(text: String) = compose.setContent {
        MikaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                Box(Modifier.width(360.dp)) { UserBubble(text, "c-test") }
            }
        }
    }

    @Test fun sentMessageShowsPhotosFilesAndWordsButNoPaths() {
        bubble(
            "帮我看看这些\n附件:/workspace/inbox/a.jpg(1 KB)\n附件:/workspace/inbox/b.jpg(1 KB)\n" +
                "附件:/workspace/inbox/c.webp(1 KB)\n附件:/workspace/inbox/租房合同 (1).pdf(48 KB)",
        )
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(3)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(1)
        compose.onNodeWithText("租房合同 (1).pdf").assertIsDisplayed()
        compose.onNodeWithText("48 KB").assertIsDisplayed()
        compose.onNodeWithText("帮我看看这些").assertIsDisplayed()
        compose.onAllNodesWithText("/workspace", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("附件:", substring = true).assertCountEquals(0)
    }

    @Test fun singlePhotoAloneHasNoWordsBubble() {
        bubble("附件:/workspace/inbox/a.jpg(1 KB)")
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(1)
        compose.onAllNodesWithText("附件", substring = true).assertCountEquals(0)
    }

    @Test fun plainMessageIsUnchanged() {
        bubble("明天早上 8 点提醒我带伞")
        compose.onNodeWithText("明天早上 8 点提醒我带伞").assertIsDisplayed()
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(0)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(0)
    }
}

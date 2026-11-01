package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.view.Display
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 本任务在哪块屏上操作:自己建的虚拟屏,还是用户的主屏([Display.DEFAULT_DISPLAY])。
 * [com.wangzan101.mika.cap.phone.a11y.AndroidScreen] 只认这一层,不关心那块屏是怎么来的。
 *
 * [currentId] 为 -1 表示还没有屏(虚拟屏到第一次打开 App 才建),这时读屏是一块空屏、其余动作失败,都不会去建屏。
 */
interface ScreenTarget {
    val width: Int
    val height: Int

    /** 已经就绪的屏号;还没有屏时为 -1。不会去建。 */
    val currentId: Int

    /** 这块屏是用户的主屏(不是 Mika 自己建的):避让用户的检查、静音、收尾时挪 App 都不做。 */
    val isMain: Boolean

    /** 取屏号,第一次调用时建(虚拟屏);建失败抛出,下次再调会重试。 */
    suspend fun acquire(): Int

    /** 实时画面;还没有屏、或这块屏不产生画面(主屏)时是空的。 */
    val frames: StateFlow<Bitmap?>

    /** 最新一帧的整幅 PNG;还没有画面时为 null。 */
    suspend fun latestFramePng(): ByteArray?

    /** 最新一帧,给只读识别用;调用方负责 recycle。没有为 null。 */
    fun latestFrameBitmap(): Bitmap?
}

/** 后台建的虚拟屏:用到时才建,建好之后一直是同一块(见 [LazyDisplay])。 */
class VirtualScreenTarget(private val display: LazyAgentDisplay) : ScreenTarget {
    override val width get() = display.width
    override val height get() = display.height
    override val currentId get() = display.current?.displayId ?: -1
    override val isMain get() = false
    override suspend fun acquire(): Int = display.get().displayId
    override val frames: StateFlow<Bitmap?> get() = display.current?.frames ?: NO_FRAMES
    override suspend fun latestFramePng(): ByteArray? = display.current?.latestFramePng()
    override fun latestFrameBitmap(): Bitmap? = display.current?.latestFrameBitmap()
}

/**
 * 用户的主屏:不建屏,屏号就是 [Display.DEFAULT_DISPLAY];截图按需问无障碍(系统限制一秒一张,
 * 见 [ScreenAccessibility.takeMainScreenshot]),没有常驻的帧流,所以这条路上不显示小画面。
 */
class MainScreenTarget(
    private val a11y: ScreenAccessibility,
    override val width: Int,
    override val height: Int,
) : ScreenTarget {
    override val currentId: Int get() = Display.DEFAULT_DISPLAY
    override val isMain: Boolean get() = true
    override suspend fun acquire(): Int = Display.DEFAULT_DISPLAY
    override val frames: StateFlow<Bitmap?> = MutableStateFlow(null)

    override suspend fun latestFramePng(): ByteArray? = withContext(Dispatchers.IO) {
        val shot = a11y.takeMainScreenshot() as? ScreenAccessibility.MainShot.Ok ?: return@withContext null
        try {
            ByteArrayOutputStream().also { shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            shot.bitmap.recycle()
        }
    }

    override fun latestFrameBitmap(): Bitmap? = null
}

private val NO_FRAMES: StateFlow<Bitmap?> = MutableStateFlow(null)

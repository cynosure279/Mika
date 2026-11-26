package com.wangzan101.mika.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareExtensionTest {
    @Test fun imagesKeepTheirOldExtensions() {
        assertEquals("jpg", shareExtension("image/jpeg"))
        assertEquals("webp", shareExtension("image/webp"))
        assertEquals("gif", shareExtension("image/gif"))
        assertEquals("png", shareExtension("image/png"))
        assertEquals("png", shareExtension("image/x-unknown"))
    }

    @Test fun videosGetVideoExtensions() {
        assertEquals("mp4", shareExtension("video/mp4"))
        assertEquals("webm", shareExtension("video/webm"))
        assertEquals("mov", shareExtension("video/quicktime"))
        assertEquals("mkv", shareExtension("video/x-matroska"))
        assertEquals("3gp", shareExtension("video/3gpp"))
        // 认不出的视频不能被当成 png
        assertEquals("mp4", shareExtension("video/x-unknown"))
    }
}

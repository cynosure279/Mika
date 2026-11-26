package com.wangzan101.mika.chat

import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class SharedDraftSizeTierTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun largeFile(file: File): File = file.also {
        it.parentFile?.mkdirs()
        RandomAccessFile(it, "rw").use { out -> out.setLength(300L shl 20) }
    }

    @Test fun largeDraftSurvivesRestartAndLoweringTheUploadTier() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        flags.setAttachmentMaxMb(1024)
        val directory = tmp.newFolder()
        val store = SharedDraftStore(directory)
        val file = largeFile(store.attachmentFile("large.bin")!!)
        assertTrue(file.length() < flags.attachmentMaxBytes())
        val draft = SharedDraft("conversation", "analyze", listOf(
            SharedDraftAttachment(file.name, file.name, file.length(), photo = false),
        ))
        assertTrue(store.save(draft))
        assertEquals(draft, SharedDraftStore(directory).read())

        flags.setAttachmentMaxMb(200)
        val restored = SharedDraftStore(directory)
        restored.cleanup()
        assertEquals(draft, restored.read())
        assertTrue(file.exists())
        assertTrue(restored.save(draft.copy(text = "edited")))
    }

    @Test fun copyUsesTheSelectedTierAndCleansUpIfTheNextImportExceedsIt() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val directory = tmp.newFolder()
        val source = largeFile(File(directory, "source.bin"))
        val temporary = File(directory, "copy.tmp")
        val destination = File(directory, "copy.bin")
        flags.setAttachmentMaxMb(1024)
        val size = source.inputStream().use {
            SharedDraftCopy.copy(it, temporary, destination, maxBytes = flags.attachmentMaxBytes()) { 10L shl 30 }
        }
        assertEquals(source.length(), size)
        assertEquals(size, destination.length())
        flags.setAttachmentMaxMb(200)
        val failedDestination = File(directory, "rejected.bin")
        val error = runCatching {
            source.inputStream().use {
                SharedDraftCopy.copy(it, temporary, failedDestination, maxBytes = flags.attachmentMaxBytes()) { 10L shl 30 }
            }
        }.exceptionOrNull()
        assertEquals(AttachmentFailureReason.TOO_LARGE, (error as? SharedDraftCopyException)?.reason)
        assertFalse(temporary.exists())
        assertFalse(failedDestination.exists())
    }
}

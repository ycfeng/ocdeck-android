package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentSelection
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageComment
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessagePartsTest {
    @Test
    fun metadataCommentTakesPriorityOverCompatibilityText() {
        val metadataComment = OpenCodeMessageComment(
            path = "src/Current.kt",
            selection = selection(7, 7),
            comment = "Metadata comment",
        )
        val comments = messageComments(
            listOf(
                part(
                    text = "The user made the following comment regarding line 2 of src/Old.kt: Compatibility comment",
                    opencodeComment = metadataComment,
                ),
            ),
        )

        assertEquals(listOf(metadataComment), comments)
    }

    @Test
    fun parsesCompatibilityCommentAndKeepsMultilineBody() {
        val comments = messageComments(
            listOf(
                part(
                    text = "The user made the following comment regarding lines 9 through 4 of src/Main.kt: First line\nSecond line",
                ),
            ),
        )

        val comment = comments.single()
        assertEquals("src/Main.kt", comment.path)
        assertEquals(9, comment.selection?.startLine)
        assertEquals(4, comment.selection?.endLine)
        assertEquals("First line\nSecond line", comment.comment)
        assertEquals(":4-9", comment.lineSuffix())
    }

    @Test
    fun commentsOnlyPartsRemainVisibleButOtherSyntheticTextDoesNot() {
        val comment = part(
            text = "The user made the following comment regarding this file of README.md: Clarify the setup",
        )
        val internalNote = part(text = "Internal synthetic note")
        val nonSyntheticComment = part(
            text = "The user made the following comment regarding line 1 of README.md: Visible text",
            synthetic = false,
        )

        assertTrue(hasMessageCommentPart(listOf(comment)))
        assertFalse(hasMessageCommentPart(listOf(internalNote)))
        assertFalse(hasMessageCommentPart(listOf(nonSyntheticComment)))
        assertEquals("", messageComments(listOf(comment)).single().lineSuffix())
    }

    @Test
    fun preservesCommentOrderAndDisplaysOnlyFilename() {
        val first = OpenCodeMessageComment("src/One.kt", selection(1, 1), "First")
        val second = OpenCodeMessageComment("E:\\repo\\Two.kt", selection(3, 5), "Second")

        val comments = messageComments(
            listOf(
                part(id = "a", opencodeComment = first),
                part(id = "b", opencodeComment = second),
            ),
        )

        assertEquals(listOf(first, second), comments)
        assertEquals("Two.kt", second.displayFilename())
        assertEquals(":3-5", second.lineSuffix())
    }

    @Test
    fun onlyDataUrlFilesAreLocalAttachments() {
        assertTrue(isFileAttachmentPart(filePart("data:text/plain;base64,SGVsbG8=")))
        assertTrue(isFileAttachmentPart(filePart("DATA:image/png;base64,AA==")))
        assertFalse(isFileAttachmentPart(filePart("file:///repo/src/Main.kt?start=8&end=8")))
        assertFalse(isFileAttachmentPart(filePart(null)))
        assertFalse(isFileAttachmentPart(part(type = "text", text = "hello", synthetic = false)))
    }

    @Test
    fun imageAttachmentsUseMimeOrDataUrlMediaType() {
        assertTrue(isImageAttachmentPart(filePart("data:application/octet-stream;base64,AA==", mime = "image/png")))
        assertTrue(isImageAttachmentPart(filePart("DATA:IMAGE/WEBP;BASE64,AA==")))
        assertFalse(isImageAttachmentPart(filePart("data:application/pdf;base64,AA==", mime = "application/pdf")))
        assertFalse(isImageAttachmentPart(filePart("file:///repo/image.png", mime = "image/png")))
        assertFalse(isImageAttachmentPart(part(type = "text", text = "data:image/png;base64,AA==", synthetic = false)))
    }

    @Test
    fun standaloneProjectFilePartsRemainVisibleAsContexts() {
        val context = filePart(
            url = "file:///repo/src/My%20File.kt",
            id = "prt_context",
            filename = null,
        )

        assertTrue(isProjectFilePart(context))
        assertTrue(hasProjectFileContextPart(listOf(context)))
        assertEquals(listOf(context), projectFileContextParts(listOf(context)))
        assertEquals("My File.kt", context.projectContextDisplayFilename())
    }

    @Test
    fun excludesOnlyTheMatchingCommentBackingFile() {
        val standalone = filePart(
            url = "file:///repo/src/Standalone.kt",
            id = "prt_standalone",
            filename = "Standalone.kt",
        )
        val comment = part(
            id = "prt_comment",
            opencodeComment = OpenCodeMessageComment(
                path = "src/My File.kt",
                selection = selection(9, 4),
                comment = "Please simplify",
            ),
        )
        val backing = filePart(
            url = "file:///repo/src/My%20File.kt?start=4&end=9",
            id = "prt_backing",
            filename = "My File.kt",
        )

        assertEquals(listOf(standalone), projectFileContextParts(listOf(standalone, comment, backing)))
    }

    private fun selection(start: Int, end: Int) = OpenCodeCommentSelection(
        startLine = start,
        startChar = 0,
        endLine = end,
        endChar = 0,
    )

    private fun filePart(
        url: String?,
        mime: String? = null,
        id: String = "prt1",
        filename: String? = null,
    ) = part(
        id = id,
        type = "file",
        text = null,
        synthetic = false,
        url = url,
        mime = mime,
        filename = filename,
    )

    private fun part(
        id: String = "prt1",
        type: String = "text",
        text: String? = null,
        synthetic: Boolean = true,
        url: String? = null,
        mime: String? = null,
        filename: String? = null,
        opencodeComment: OpenCodeMessageComment? = null,
    ) = OpenCodeMessagePart(
        id = id,
        messageId = "msg1",
        sessionId = "ses1",
        type = type,
        text = text,
        synthetic = synthetic,
        mime = mime,
        url = url,
        filename = filename,
        opencodeComment = opencodeComment,
    )
}

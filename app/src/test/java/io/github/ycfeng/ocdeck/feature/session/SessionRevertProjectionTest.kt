package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentSelection
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageComment
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionRevert
import io.github.ycfeng.ocdeck.domain.prompt.DataUrlPayloadInspection
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContextLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRevertProjectionTest {
    @Test
    fun hidesBoundaryMessageAndEverythingAfterIt() {
        val messages = listOf(
            message("msg_100", "user", "A"),
            message("msg_110", "assistant", "A"),
            message("msg_200", "user", "B"),
            message("msg_210", "assistant", "B"),
            message("msg_300", "user", "C"),
        )

        val projection = projectSessionMessages(messages, OpenCodeSessionRevert("msg_200"))

        assertEquals(listOf("msg_100", "msg_110"), projection.visibleMessages.map { it.id })
        assertEquals(listOf("msg_200", "msg_300"), projection.revertedUserMessages.map { it.id })
    }

    @Test
    fun returnsAllMessagesWhenThereIsNoRevertMarker() {
        val messages = listOf(message("msg_100", "user", "A"))

        val projection = projectSessionMessages(messages, revert = null)

        assertEquals(messages, projection.visibleMessages)
        assertEquals(emptyList<OpenCodeMessage>(), projection.revertedUserMessages)
    }

    @Test
    fun exposesNewOptimisticBranchWhileOldMarkerIsStillPresent() {
        val messages = listOf(
            message("msg_100", "user", "A"),
            message("msg_200", "user", "B"),
            message("msg_300", "user", "C"),
            message("msg_400", "user", "D"),
            message("msg_410", "assistant", "D"),
        )

        val projection = projectSessionMessages(
            messages = messages,
            revert = OpenCodeSessionRevert("msg_200"),
            branchStartMessageId = "msg_400",
        )

        assertEquals(listOf("msg_100", "msg_400", "msg_410"), projection.visibleMessages.map { it.id })
        assertEquals(emptyList<OpenCodeMessage>(), projection.revertedUserMessages)
    }

    @Test
    fun restoresTextAndOnlyLocalDataAttachmentsWithoutProjectDirectory() {
        val message = message("msg_200", "user", "Prompt").copy(
            parts = listOf(
                OpenCodeMessagePart(
                    id = "prt_image",
                    messageId = "msg_200",
                    sessionId = "ses_1",
                    type = "file",
                    text = null,
                    synthetic = false,
                    filename = "image.png",
                    mime = "image/png",
                    url = "data:image/png;base64,AA==",
                ),
                OpenCodeMessagePart(
                    id = "prt_server",
                    messageId = "msg_200",
                    sessionId = "ses_1",
                    type = "file",
                    text = null,
                    synthetic = false,
                    filename = "Main.kt",
                    mime = "text/plain",
                    url = "file:///repo/Main.kt",
                ),
            ),
        )

        val draft = message.toSessionRevertDraft()

        assertEquals("Prompt", draft.text)
        assertEquals(1, draft.attachments.size)
        assertEquals("image.png", draft.attachments.single().filename)
        assertEquals("data:image/png;base64,AA==", draft.attachments.single().dataUrl)
        assertEquals(1L, draft.attachments.single().sizeBytes)
        assertEquals(emptyList<io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext>(), draft.projectFileContexts)
    }

    @Test
    fun rejectsDraftWhenAnyStandaloneProjectContextIsOutsideProjectRoot() {
        val message = message("msg_200", "user", "Prompt").copy(
            parts = listOf(
                projectFilePart("prt_valid", "file:///repo/src/Main.kt", "Main.kt"),
                projectFilePart("prt_outside", "file:///other/Secret.kt", "Secret.kt"),
            ),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(1, draft.projectFileContexts.size)
        assertEquals("prt_valid", draft.projectFileContexts.single().id)
        assertEquals("src/Main.kt", draft.projectFileContexts.single().relativePath)
        assertEquals(SessionRevertDraftFailure.ProjectContextInvalid, draft.failure)
    }

    @Test
    fun projectContextOnlyOutsideRootProducesExplicitFailure() {
        val message = message("msg_200", "user", "").copy(
            parts = listOf(projectFilePart("prt_outside", "file:///other/Secret.kt", "Secret.kt")),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(emptyList<io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext>(), draft.projectFileContexts)
        assertEquals(SessionRevertDraftFailure.ProjectContextInvalid, draft.failure)
        assertEquals(false, draft.hasRecoverableContent)
    }

    @Test
    fun mixedTextAndInvalidProjectContextRejectsPartialRestore() {
        val message = message("msg_200", "user", "Prompt").copy(
            parts = listOf(projectFilePart("prt_outside", "file:///other/Secret.kt", "Secret.kt")),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals("Prompt", draft.text)
        assertEquals(SessionRevertDraftFailure.ProjectContextInvalid, draft.failure)
    }

    @Test
    fun unpairedRangedOrFragmentFileUrlIsInvalidProjectContext() {
        val rangedMessage = message("msg_200", "user", "").copy(
            parts = listOf(projectFilePart("prt_query", "file:///repo/src/Main.kt?start=1&end=2", "Main.kt")),
        )
        val fragmentMessage = message("msg_200", "user", "").copy(
            parts = listOf(projectFilePart("prt_fragment", "file:///repo/src/Main.kt#selection", "Main.kt")),
        )

        assertEquals(
            SessionRevertDraftFailure.ProjectContextInvalid,
            rangedMessage.toSessionRevertDraft(projectDirectory = "/repo").failure,
        )
        assertEquals(
            SessionRevertDraftFailure.ProjectContextInvalid,
            fragmentMessage.toSessionRevertDraft(projectDirectory = "/repo").failure,
        )
    }

    @Test
    fun excludesCommentBackingFileWhileRestoringStandaloneContext() {
        val comment = OpenCodeMessageComment(
            path = "src/Commented.kt",
            selection = null,
            comment = "Please simplify",
        )
        val message = message("msg_200", "user", "").copy(
            parts = listOf(
                projectFilePart("prt_standalone", "file:///repo/src/Standalone.kt", "Standalone.kt"),
                OpenCodeMessagePart(
                    id = "prt_comment",
                    messageId = "msg_200",
                    sessionId = "ses_1",
                    type = "text",
                    text = null,
                    synthetic = true,
                    opencodeComment = comment,
                ),
                projectFilePart("prt_backing", "file:///repo/src/Commented.kt", "Commented.kt"),
            ),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(listOf("src/Standalone.kt"), draft.projectFileContexts.map { it.relativePath })
    }

    @Test
    fun matchedCommentBackingRangeDoesNotCauseProjectContextFailure() {
        val comment = OpenCodeMessageComment(
            path = "src/Commented.kt",
            selection = OpenCodeCommentSelection(
                startLine = 9,
                startChar = 0,
                endLine = 4,
                endChar = 0,
            ),
            comment = "Please simplify",
        )
        val message = message("msg_200", "user", "Prompt").copy(
            parts = listOf(
                OpenCodeMessagePart(
                    id = "prt_comment",
                    messageId = "msg_200",
                    sessionId = "ses_1",
                    type = "text",
                    text = null,
                    synthetic = true,
                    opencodeComment = comment,
                ),
                projectFilePart(
                    "prt_backing",
                    "file:///repo/src/Commented.kt?start=4&end=9",
                    "Commented.kt",
                ),
            ),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(emptyList<io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext>(), draft.projectFileContexts)
        assertEquals(null, draft.failure)
    }

    @Test
    fun blankProjectContextPartIdIsInvalid() {
        val message = message("msg_200", "user", "").copy(
            parts = listOf(projectFilePart("", "file:///repo/src/Main.kt", "Main.kt")),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(SessionRevertDraftFailure.ProjectContextInvalid, draft.failure)
    }

    @Test
    fun duplicatePosixProjectContextsAreInvalid() {
        val message = message("msg_200", "user", "").copy(
            parts = listOf(
                projectFilePart("prt_first", "file:///repo/src/Main.kt", "Main.kt"),
                projectFilePart("prt_second", "file:///repo/src/Main.kt", "Main.kt"),
            ),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(SessionRevertDraftFailure.ProjectContextInvalid, draft.failure)
    }

    @Test
    fun duplicateWindowsProjectContextsIgnoreCase() {
        val message = message("msg_200", "user", "").copy(
            parts = listOf(
                projectFilePart("prt_first", "file:///C:/repo/src/Main.kt", "Main.kt"),
                projectFilePart("prt_second", "file:///C:/REPO/src/main.kt", "main.kt"),
            ),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "C:\\repo")

        assertEquals(SessionRevertDraftFailure.ProjectContextInvalid, draft.failure)
    }

    @Test
    fun projectContextCountLimitIsReported() {
        val message = message("msg_200", "user", "").copy(
            parts = List(ProjectFileContextLimits.MAX_CONTEXT_COUNT + 1) { index ->
                projectFilePart("prt_$index", "file:///repo/src/File$index.kt", "File$index.kt")
            },
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals(ProjectFileContextLimits.MAX_CONTEXT_COUNT, draft.projectFileContexts.size)
        assertEquals(SessionRevertDraftFailure.ProjectContextCountLimit, draft.failure)
        assertEquals(true, draft.hasRecoverableContent)
    }

    @Test
    fun projectContextOnlyMessageProducesRecoverableDraft() {
        val message = message("msg_200", "user", "").copy(
            parts = listOf(projectFilePart("prt_context", "file:///repo/src/Main.kt", "Main.kt")),
        )

        val draft = message.toSessionRevertDraft(projectDirectory = "/repo")

        assertEquals("", draft.text)
        assertEquals(emptyList<io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment>(), draft.attachments)
        assertEquals(listOf("src/Main.kt"), draft.projectFileContexts.map { it.relativePath })
        assertEquals(true, draft.hasRecoverableContent)
    }

    @Test
    fun ignoresMalformedDataUrlAttachmentsInsteadOfRestoringThemAsZeroBytes() {
        val message = message("msg_200", "user", "Prompt").copy(
            parts = listOf(
                filePart("prt_invalid_character", "data:text/plain;base64,%%%"),
                filePart("prt_invalid_length", "data:text/plain;base64,A"),
                filePart("prt_not_base64", "data:text/plain,hello"),
            ),
        )

        val draft = message.toSessionRevertDraft()

        assertEquals(emptyList<io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment>(), draft.attachments)
        assertEquals(SessionRevertDraftFailure.AttachmentInvalid, draft.failure)
    }

    @Test
    fun calculatesUnpaddedDataUrlSizeWithoutDecodingThePayload() {
        val inspection = inspectBase64DataUrl("data:application/octet-stream;base64,AQI")

        assertEquals(
            DataUrlPayloadInspection.Valid(mediaType = "application/octet-stream", sizeBytes = 2L),
            inspection,
        )
    }

    @Test
    fun stopsDataUrlInspectionWhenDecodedSizeExceedsLimit() {
        val inspection = inspectBase64DataUrl(
            dataUrl = "data:application/octet-stream;base64,AAAA",
            maxBytes = 2L,
        )

        assertEquals(DataUrlPayloadInspection.TooLarge, inspection)
    }

    @Test
    fun revertDraftAppliesAttachmentCountLimit() {
        val message = message("msg_200", "user", "Prompt").copy(
            parts = List(11) { index ->
                filePart("prt_$index", "data:text/plain;base64,")
            },
        )

        val draft = message.toSessionRevertDraft()

        assertEquals(10, draft.attachments.size)
        assertEquals(0L, draft.attachments.sumOf { it.sizeBytes ?: -1L })
        assertEquals(SessionRevertDraftFailure.AttachmentCountLimit, draft.failure)
    }

    @Test
    fun attachmentOnlyMessageProducesRecoverableDraft() {
        val message = message("msg_200", "user", "").copy(
            parts = listOf(filePart("prt_file", "data:text/plain;base64,SGk=")),
        )

        val draft = message.toSessionRevertDraft()

        assertEquals("", draft.text)
        assertEquals(1, draft.attachments.size)
        assertEquals(null, draft.failure)
        assertEquals(true, draft.hasRecoverableContent)
    }

    private fun message(id: String, role: String, text: String) = OpenCodeMessage(
        id = id,
        sessionId = "ses_1",
        role = role,
        text = text,
    )

    private fun filePart(id: String, dataUrl: String) = OpenCodeMessagePart(
        id = id,
        messageId = "msg_200",
        sessionId = "ses_1",
        type = "file",
        text = null,
        synthetic = false,
        filename = "$id.txt",
        mime = "text/plain",
        url = dataUrl,
    )

    private fun projectFilePart(id: String, url: String, filename: String) = OpenCodeMessagePart(
        id = id,
        messageId = "msg_200",
        sessionId = "ses_1",
        type = "file",
        text = null,
        synthetic = false,
        filename = filename,
        mime = "text/plain",
        url = url,
    )
}

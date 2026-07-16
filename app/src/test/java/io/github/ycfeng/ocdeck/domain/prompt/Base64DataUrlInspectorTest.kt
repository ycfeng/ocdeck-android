package io.github.ycfeng.ocdeck.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Base64DataUrlInspectorTest {
    @Test
    fun acceptsPaddedAndUnpaddedPayloadsWithoutDecodingThem() {
        assertEquals(
            DataUrlPayloadInspection.Valid("text/plain", 2L),
            inspectBase64DataUrl("data:text/plain;base64,SGk="),
        )
        assertEquals(
            DataUrlPayloadInspection.Valid("text/plain", 2L),
            inspectBase64DataUrl("data:text/plain;base64,SGk"),
        )
    }

    @Test
    fun rejectsWhitespaceAndMalformedPadding() {
        assertEquals(
            DataUrlPayloadInspection.Invalid,
            inspectBase64DataUrl("data:text/plain;base64,SG k="),
        )
        assertEquals(
            DataUrlPayloadInspection.Invalid,
            inspectBase64DataUrl("data:text/plain;base64,SG=k"),
        )
    }

    @Test
    fun boundsHeaderAndEncodedPayloadBeforeFullDecodeAllocation() {
        val oversizedHeader = "data:" + "a".repeat(AttachmentLimits.MAX_DATA_URL_HEADER_CHARACTERS + 1) + ";base64,"

        assertEquals(DataUrlPayloadInspection.TooLarge, inspectBase64DataUrl(oversizedHeader))
        assertEquals(
            DataUrlPayloadInspection.TooLarge,
            inspectBase64DataUrl("data:application/octet-stream;base64,AAAA", maxBytes = 2L),
        )
    }

    @Test
    fun attachmentValidatorEnforcesCountAndTotalBudget() {
        val emptyAttachment = PromptAttachment(
            id = "empty",
            filename = "empty.txt",
            mime = "text/plain",
            dataUrl = "data:text/plain;base64,",
            sizeBytes = 0,
        )
        assertThrows(PromptAttachmentCountLimitException::class.java) {
            validatePromptAttachments(
                attachments = listOf(emptyAttachment, emptyAttachment.copy(id = "second")),
                maxAttachmentCount = 1,
            )
        }
        assertThrows(PromptAttachmentTotalSizeLimitException::class.java) {
            validatePromptAttachments(
                attachments = listOf(
                    emptyAttachment.copy(id = "one", dataUrl = "data:text/plain;base64,AA==", sizeBytes = 1),
                    emptyAttachment.copy(id = "two", dataUrl = "data:text/plain;base64,AA==", sizeBytes = 1),
                ),
                maxTotalBytes = 1,
            )
        }
    }

    @Test
    fun attachmentValidatorRejectsBlankRequiredMetadata() {
        val attachment = PromptAttachment(
            id = "",
            filename = "note.txt",
            mime = "text/plain",
            dataUrl = "data:text/plain;base64,",
            sizeBytes = 0,
        )

        assertThrows(PromptAttachmentMetadataInvalidException::class.java) {
            validatePromptAttachments(listOf(attachment))
        }
    }
}

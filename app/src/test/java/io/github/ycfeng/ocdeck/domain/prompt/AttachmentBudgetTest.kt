package io.github.ycfeng.ocdeck.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentBudgetTest {
    @Test
    fun countLimitIncludesExistingAttachmentsAndKeepsPartialSuccess() {
        val existing = List(AttachmentLimits.MAX_ATTACHMENT_COUNT - 1) { index ->
            attachment("existing_$index", 0L)
        }

        val admission = AttachmentBudget.from(existing).admit(
            listOf(
                attachment("first", 0L),
                attachment("second", 0L),
            ),
        )

        assertEquals(listOf("first"), admission.accepted.map { it.id })
        assertEquals(AttachmentLimits.MAX_ATTACHMENT_COUNT, admission.budget.attachmentCount)
        assertEquals(AttachmentBudgetFailure.CountLimit, admission.failure)
    }

    @Test
    fun totalLimitIncludesExistingBytesAndAllowsExactBoundary() {
        val budget = AttachmentBudget.from(
            listOf(attachment("existing", AttachmentLimits.MAX_TOTAL_BYTES - 2L)),
        )

        val exact = budget.add(2L)
        val over = budget.add(3L)

        assertNull(exact.failure)
        assertEquals(AttachmentLimits.MAX_TOTAL_BYTES, exact.budget.totalSizeBytes)
        assertEquals(AttachmentBudgetFailure.TotalSizeLimit, over.failure)
    }

    @Test
    fun perFileLimitAllowsExactBoundaryAndRejectsOneByteOver() {
        val budget = AttachmentBudget.from(emptyList())

        assertNull(budget.add(AttachmentLimits.MAX_FILE_BYTES).failure)
        assertEquals(
            AttachmentBudgetFailure.TooLarge,
            budget.add(AttachmentLimits.MAX_FILE_BYTES + 1L).failure,
        )
    }

    @Test
    fun unknownExistingSizeFailsClosedForAdditionalPayloads() {
        val budget = AttachmentBudget.from(listOf(attachment("unknown", null)))

        val update = budget.add(1L)

        assertEquals(AttachmentBudgetFailure.TotalSizeLimit, update.failure)
    }

    @Test
    fun localDataUrlAttachmentUsesActualPayloadSize() {
        val attachment = createLocalDataUrlAttachment(
            id = "part",
            filename = "sample.txt",
            mime = "text/plain",
            bytes = byteArrayOf(1, 2, 3),
        )

        assertEquals(3L, attachment.sizeBytes)
        assertEquals("data:text/plain;base64,AQID", attachment.dataUrl)
    }

    private fun attachment(id: String, sizeBytes: Long?): PromptAttachment = PromptAttachment(
        id = id,
        filename = "$id.txt",
        mime = "text/plain",
        dataUrl = "data:text/plain;base64,",
        sizeBytes = sizeBytes,
    )
}

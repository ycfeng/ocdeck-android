package io.github.ycfeng.ocdeck.domain.prompt

internal object AttachmentLimits {
    const val MEBIBYTE_BYTES = 1024L * 1024L
    const val MAX_FILE_MIB = 20
    const val MAX_TOTAL_MIB = 20
    const val MAX_FILE_BYTES = MAX_FILE_MIB * MEBIBYTE_BYTES
    const val MAX_TOTAL_BYTES = MAX_TOTAL_MIB * MEBIBYTE_BYTES
    const val MAX_ATTACHMENT_COUNT = 10
    const val MAX_DATA_URL_HEADER_CHARACTERS = 4 * 1024

    const val READ_BUFFER_BYTES = 8 * 1024
    const val MAX_INITIAL_CAPACITY_BYTES = 64 * 1024
}

internal enum class AttachmentBudgetFailure {
    CountLimit,
    TotalSizeLimit,
    TooLarge,
}

internal data class AttachmentBudget(
    val attachmentCount: Int,
    val totalSizeBytes: Long,
    val hasUnknownSize: Boolean,
) {
    val remainingCount: Int
        get() = (AttachmentLimits.MAX_ATTACHMENT_COUNT - attachmentCount).coerceAtLeast(0)

    val remainingBytes: Long
        get() = if (hasUnknownSize) {
            0L
        } else {
            (AttachmentLimits.MAX_TOTAL_BYTES - totalSizeBytes).coerceAtLeast(0L)
        }

    fun add(sizeBytes: Long?): AttachmentBudgetUpdate {
        val failure = when {
            attachmentCount >= AttachmentLimits.MAX_ATTACHMENT_COUNT -> AttachmentBudgetFailure.CountLimit
            sizeBytes == null || sizeBytes < 0L -> AttachmentBudgetFailure.TotalSizeLimit
            sizeBytes > AttachmentLimits.MAX_FILE_BYTES -> AttachmentBudgetFailure.TooLarge
            hasUnknownSize || totalSizeBytes > AttachmentLimits.MAX_TOTAL_BYTES -> AttachmentBudgetFailure.TotalSizeLimit
            sizeBytes > remainingBytes -> AttachmentBudgetFailure.TotalSizeLimit
            else -> null
        }
        return if (failure == null) {
            AttachmentBudgetUpdate(
                budget = copy(
                    attachmentCount = attachmentCount + 1,
                    totalSizeBytes = totalSizeBytes + sizeBytes!!,
                ),
            )
        } else {
            AttachmentBudgetUpdate(budget = this, failure = failure)
        }
    }

    fun admit(attachments: List<PromptAttachment>): AttachmentAdmission {
        var nextBudget = this
        var firstFailure: AttachmentBudgetFailure? = null
        val accepted = buildList {
            attachments.forEach { attachment ->
                val update = nextBudget.add(attachment.sizeBytes)
                if (update.failure == null) {
                    add(attachment)
                    nextBudget = update.budget
                } else if (firstFailure == null) {
                    firstFailure = update.failure
                }
            }
        }
        return AttachmentAdmission(
            accepted = accepted,
            budget = nextBudget,
            failure = firstFailure,
        )
    }

    companion object {
        fun from(attachments: List<PromptAttachment>): AttachmentBudget {
            var totalSizeBytes = 0L
            var hasUnknownSize = false
            attachments.forEach { attachment ->
                val sizeBytes = attachment.sizeBytes
                if (sizeBytes == null || sizeBytes < 0L) {
                    hasUnknownSize = true
                } else {
                    totalSizeBytes = saturatedAdd(totalSizeBytes, sizeBytes)
                }
            }
            return AttachmentBudget(
                attachmentCount = attachments.size,
                totalSizeBytes = totalSizeBytes,
                hasUnknownSize = hasUnknownSize,
            )
        }

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

internal data class AttachmentBudgetUpdate(
    val budget: AttachmentBudget,
    val failure: AttachmentBudgetFailure? = null,
)

internal data class AttachmentAdmission(
    val accepted: List<PromptAttachment>,
    val budget: AttachmentBudget,
    val failure: AttachmentBudgetFailure? = null,
) {
    override fun toString(): String =
        "AttachmentAdmission(acceptedCount=${accepted.size}, budget=$budget, failure=$failure)"
}

package io.github.ycfeng.ocdeck.domain.prompt

internal sealed interface DataUrlPayloadInspection {
    data class Valid(
        val mediaType: String?,
        val sizeBytes: Long,
    ) : DataUrlPayloadInspection

    data object Invalid : DataUrlPayloadInspection

    data object TooLarge : DataUrlPayloadInspection
}

internal fun inspectBase64DataUrl(
    dataUrl: String,
    maxBytes: Long = AttachmentLimits.MAX_FILE_BYTES,
    maxHeaderCharacters: Int = AttachmentLimits.MAX_DATA_URL_HEADER_CHARACTERS,
): DataUrlPayloadInspection {
    require(maxBytes >= 0L) { "Data URL payload limit must not be negative" }
    require(maxHeaderCharacters >= 0) { "Data URL header limit must not be negative" }
    if (!dataUrl.startsWith("data:", ignoreCase = true)) return DataUrlPayloadInspection.Invalid

    val maximumCommaIndex = 5L + maxHeaderCharacters.toLong()
    var headerEnd = -1
    var index = 5
    while (index < dataUrl.length && index.toLong() <= maximumCommaIndex) {
        val character = dataUrl[index]
        if (character == ',') {
            headerEnd = index
            break
        }
        if (character.isPromptDataUrlWhitespace()) return DataUrlPayloadInspection.Invalid
        index += 1
    }
    if (headerEnd < 0) {
        return if (dataUrl.length.toLong() > maximumCommaIndex) {
            DataUrlPayloadInspection.TooLarge
        } else {
            DataUrlPayloadInspection.Invalid
        }
    }

    val base64MarkerStart = dataUrl.lastIndexOf(';', startIndex = headerEnd - 1)
    if (
        base64MarkerStart < 5 ||
        headerEnd - base64MarkerStart - 1 != BASE64_MARKER.length ||
        !dataUrl.regionMatches(
            thisOffset = base64MarkerStart + 1,
            other = BASE64_MARKER,
            otherOffset = 0,
            length = BASE64_MARKER.length,
            ignoreCase = true,
        )
    ) {
        return DataUrlPayloadInspection.Invalid
    }

    val payloadStart = headerEnd + 1
    val encodedPayloadLength = dataUrl.length.toLong() - payloadStart.toLong()
    if (encodedPayloadLength > maximumEncodedBase64Length(maxBytes)) {
        return DataUrlPayloadInspection.TooLarge
    }

    val mediaTypeEnd = dataUrl.indexOf(';', startIndex = 5)
        .takeIf { it in 5 until headerEnd }
        ?: headerEnd
    val mediaType = dataUrl.substring(5, mediaTypeEnd).takeIf { it.isNotEmpty() }
    var dataSymbolCount = 0L
    var paddingCount = 0
    var sawPadding = false
    for (payloadIndex in payloadStart until dataUrl.length) {
        when (val character = dataUrl[payloadIndex]) {
            '=' -> {
                sawPadding = true
                paddingCount += 1
                if (paddingCount > 2) return DataUrlPayloadInspection.Invalid
            }
            else -> {
                if (
                    character.isPromptDataUrlWhitespace() ||
                    sawPadding ||
                    !character.isBase64DataCharacter()
                ) {
                    return DataUrlPayloadInspection.Invalid
                }
                dataSymbolCount += 1L
                if (decodedBase64Size(dataSymbolCount) > maxBytes) {
                    return DataUrlPayloadInspection.TooLarge
                }
            }
        }
    }

    val remainder = (dataSymbolCount % BASE64_GROUP_CHARACTERS).toInt()
    if (paddingCount == 0) {
        if (remainder == 1) return DataUrlPayloadInspection.Invalid
    } else {
        val expectedPadding = when (remainder) {
            2 -> 2
            3 -> 1
            else -> return DataUrlPayloadInspection.Invalid
        }
        if (
            paddingCount != expectedPadding ||
            (dataSymbolCount + paddingCount) % BASE64_GROUP_CHARACTERS != 0L
        ) {
            return DataUrlPayloadInspection.Invalid
        }
    }
    val sizeBytes = decodedBase64Size(dataSymbolCount)
    return if (sizeBytes > maxBytes) {
        DataUrlPayloadInspection.TooLarge
    } else {
        DataUrlPayloadInspection.Valid(mediaType = mediaType, sizeBytes = sizeBytes)
    }
}

internal fun validatePromptAttachments(
    attachments: List<PromptAttachment>,
    maxAttachmentCount: Int = AttachmentLimits.MAX_ATTACHMENT_COUNT,
    maxFileBytes: Long = AttachmentLimits.MAX_FILE_BYTES,
    maxTotalBytes: Long = AttachmentLimits.MAX_TOTAL_BYTES,
    maxHeaderCharacters: Int = AttachmentLimits.MAX_DATA_URL_HEADER_CHARACTERS,
): List<PromptAttachment> {
    require(maxAttachmentCount >= 0) { "Attachment count limit must not be negative" }
    require(maxFileBytes >= 0L) { "Attachment file limit must not be negative" }
    require(maxTotalBytes >= 0L) { "Attachment total limit must not be negative" }
    if (attachments.size > maxAttachmentCount) throw PromptAttachmentCountLimitException()

    var totalBytes = 0L
    attachments.forEach { attachment ->
        if (attachment.id.isBlank() || attachment.filename.isBlank() || attachment.mime.isBlank()) {
            throw PromptAttachmentMetadataInvalidException()
        }
        val declaredSize = attachment.sizeBytes
            ?: throw PromptAttachmentMetadataInvalidException()
        if (declaredSize < 0L) throw PromptAttachmentMetadataInvalidException()
        if (declaredSize > maxFileBytes) throw PromptAttachmentFileTooLargeException()
        val inspection = inspectBase64DataUrl(
            dataUrl = attachment.dataUrl,
            maxBytes = maxFileBytes,
            maxHeaderCharacters = maxHeaderCharacters,
        )
        val inspectedSize = when (inspection) {
            DataUrlPayloadInspection.Invalid -> throw PromptAttachmentMalformedException()
            DataUrlPayloadInspection.TooLarge -> throw PromptAttachmentFileTooLargeException()
            is DataUrlPayloadInspection.Valid -> inspection.sizeBytes
        }
        if (declaredSize != inspectedSize) throw PromptAttachmentMetadataInvalidException()
        if (totalBytes > maxTotalBytes - inspectedSize) {
            throw PromptAttachmentTotalSizeLimitException()
        }
        totalBytes += inspectedSize
    }
    return attachments.toList()
}

private fun maximumEncodedBase64Length(maxBytes: Long): Long {
    if (maxBytes > (Long.MAX_VALUE / 4L) * 3L - 2L) return Long.MAX_VALUE
    return ((maxBytes + 2L) / 3L) * 4L
}

private fun decodedBase64Size(dataSymbolCount: Long): Long {
    val completeGroups = dataSymbolCount / BASE64_GROUP_CHARACTERS
    val remainder = dataSymbolCount % BASE64_GROUP_CHARACTERS
    return completeGroups * BASE64_GROUP_BYTES + when (remainder) {
        2L -> 1L
        3L -> 2L
        else -> 0L
    }
}

private fun Char.isPromptDataUrlWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

private fun Char.isBase64DataCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/'

private const val BASE64_MARKER = "base64"
private const val BASE64_GROUP_CHARACTERS = 4L
private const val BASE64_GROUP_BYTES = 3L

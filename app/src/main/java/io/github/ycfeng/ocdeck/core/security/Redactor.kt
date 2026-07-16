package io.github.ycfeng.ocdeck.core.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque

enum class RedactionScope {
    Generic,
    Config,
    ProviderAuth,
    Env,
    Headers,
    CustomHeaders,
}

class Redactor(
    private val maxJsonDepth: Int = DEFAULT_MAX_JSON_DEPTH,
) {
    init {
        require(maxJsonDepth >= 0) { "maxJsonDepth must be non-negative" }
    }

    fun redact(value: String): String {
        if (value.isEmpty()) return value

        return redactKeyValues(
            redactAuthenticationSchemes(
                redactQueryParameters(
                    redactFreeTextUrls(
                        redactHeaderLines(
                            redactPemBlocks(value),
                        ),
                    ),
                ),
            ),
        )
    }

    fun redact(
        value: JsonElement,
        scope: RedactionScope = RedactionScope.Generic,
    ): JsonElement = redactJson(value, scope, depth = 0)

    fun redactUrl(value: HttpUrl): HttpUrl {
        val builder = value.newBuilder()
        if (value.username.isNotEmpty()) {
            builder.username(REDACTED)
        }
        if (value.password.isNotEmpty()) {
            builder.password(REDACTED)
        }
        if (value.querySize > 0) {
            builder.query(null)
            repeat(value.querySize) { index ->
                val name = value.queryParameterName(index)
                val queryValue = value.queryParameterValue(index)
                builder.addQueryParameter(
                    name,
                    if (isDirectSensitiveKey(name)) REDACTED else queryValue,
                )
            }
        }
        if (value.fragment != null) {
            builder.fragment(REDACTED)
        }
        return builder.build()
    }

    fun redactHeaders(
        value: Headers,
        scope: RedactionScope = RedactionScope.Headers,
    ): Headers {
        val redactAll = scope.redactsEveryLeaf()
        val builder = Headers.Builder()
        repeat(value.size) { index ->
            val name = value.name(index)
            builder.add(
                name,
                if (redactAll || isSensitiveHeaderName(name)) REDACTED else value.value(index),
            )
        }
        return builder.build()
    }

    private fun redactJson(
        value: JsonElement,
        scope: RedactionScope,
        depth: Int,
    ): JsonElement {
        if (value === JsonNull) return value
        if (depth > maxJsonDepth) return REDACTED_JSON
        if (scope.redactsEveryLeaf()) return redactEveryLeaf(value, depth)

        return when (value) {
            is JsonObject -> JsonObject(
                value.mapValues { (key, child) ->
                    when {
                        isDirectSensitiveKey(key) -> REDACTED_JSON
                        else -> {
                            val childScope = containerScope(key, scope) ?: scope
                            redactJson(child, childScope, depth + 1)
                        }
                    }
                },
            )

            is JsonArray -> JsonArray(value.map { redactJson(it, scope, depth + 1) })
            else -> value
        }
    }

    private fun redactEveryLeaf(value: JsonElement, depth: Int): JsonElement {
        if (value === JsonNull) return value
        if (depth > maxJsonDepth) return REDACTED_JSON

        return when (value) {
            is JsonObject -> JsonObject(value.mapValues { (_, child) -> redactEveryLeaf(child, depth + 1) })
            is JsonArray -> JsonArray(value.map { redactEveryLeaf(it, depth + 1) })
            else -> REDACTED_JSON
        }
    }

    private fun RedactionScope.redactsEveryLeaf(): Boolean =
        this == RedactionScope.ProviderAuth ||
            this == RedactionScope.Env ||
            this == RedactionScope.CustomHeaders

    private fun containerScope(key: String, parentScope: RedactionScope): RedactionScope? {
        if (parentScope == RedactionScope.Headers) return null

        val tokens = keyTokens(key)
        return when {
            tokens == listOf("env") ||
                tokens == listOf("env", "vars") ||
                tokens == listOf("environment", "variables") -> RedactionScope.Env

            tokens == listOf("provider", "auth") ||
                tokens == listOf("provider", "authentication") ||
                parentScope == RedactionScope.Config &&
                (tokens == listOf("auth") || tokens == listOf("authentication")) -> RedactionScope.ProviderAuth

            tokens == listOf("custom", "headers") ||
                tokens == listOf("custom", "provider", "headers") ||
                tokens == listOf("provider", "headers") ||
                tokens == listOf("request", "headers") -> RedactionScope.CustomHeaders

            tokens == listOf("headers") -> {
                if (parentScope == RedactionScope.Config) {
                    RedactionScope.CustomHeaders
                } else {
                    RedactionScope.Headers
                }
            }

            else -> null
        }
    }

    private fun redactPemBlocks(value: String): String {
        var searchFrom = 0
        var copiedUntil = 0
        var changed = false
        val output = StringBuilder(value.length)

        while (searchFrom < value.length) {
            val begin = value.indexOf(PEM_BEGIN_PREFIX, searchFrom)
            if (begin < 0) break

            val labelStart = begin + PEM_BEGIN_PREFIX.length
            val labelEnd = value.indexOf(PEM_MARKER_SUFFIX, labelStart)
            if (labelEnd < 0) break

            val label = value.substring(labelStart, labelEnd)
            if (!endsWithTokens(keyTokens(label), listOf("private", "key"))) {
                searchFrom = labelEnd + PEM_MARKER_SUFFIX.length
                continue
            }

            changed = true
            output.append(value, copiedUntil, begin)
            output.append(REDACTED)

            val endMarker = "-----END $label-----"
            val end = value.indexOf(endMarker, labelEnd + PEM_MARKER_SUFFIX.length)
            if (end < 0) {
                copiedUntil = value.length
                searchFrom = value.length
                break
            }

            copiedUntil = end + endMarker.length
            searchFrom = copiedUntil
        }

        if (!changed) return value
        if (copiedUntil < value.length) {
            output.append(value, copiedUntil, value.length)
        }
        return output.toString()
    }

    private fun redactHeaderLines(value: String): String {
        val output = StringBuilder(value.length)
        var lineStart = 0
        var previousHeaderWasSensitive = false
        var previousHeaderIndent = 0

        while (lineStart < value.length) {
            var lineEnd = lineStart
            while (lineEnd < value.length && value[lineEnd] != '\r' && value[lineEnd] != '\n') {
                lineEnd++
            }
            val line = value.substring(lineStart, lineEnd)
            val header = if (line.isBlank()) null else parseHeaderLine(line)

            when {
                line.isEmpty() || line.isBlank() -> {
                    output.append(line)
                    previousHeaderWasSensitive = false
                    previousHeaderIndent = 0
                }

                previousHeaderWasSensitive &&
                    line.first().isInlineWhitespace() &&
                    (header == null || header.indent > previousHeaderIndent) -> {
                    val contentStart = line.indexOfFirst { !it.isInlineWhitespace() }
                        .let { if (it < 0) line.length else it }
                    output.append(line, 0, contentStart)
                    output.append(REDACTED)
                }

                else -> {
                    if (header != null && isSensitiveHeaderName(header.name)) {
                        output.append(line, 0, header.valueStart)
                        output.append(REDACTED)
                        previousHeaderWasSensitive = true
                        previousHeaderIndent = header.indent
                    } else {
                        output.append(line)
                        previousHeaderWasSensitive = false
                        previousHeaderIndent = 0
                    }
                }
            }

            if (lineEnd < value.length) {
                if (value[lineEnd] == '\r' && lineEnd + 1 < value.length && value[lineEnd + 1] == '\n') {
                    output.append("\r\n")
                    lineStart = lineEnd + 2
                } else {
                    output.append(value[lineEnd])
                    lineStart = lineEnd + 1
                }
            } else {
                lineStart = lineEnd
            }
        }

        return output.toString()
    }

    private fun parseHeaderLine(line: String): HeaderLine? {
        var cursor = 0
        while (cursor < line.length && line[cursor].isInlineWhitespace()) cursor++
        val nameStart = cursor
        while (cursor < line.length && line[cursor].isHeaderNameCharacter()) cursor++
        if (cursor == nameStart) return null
        val nameEnd = cursor
        while (cursor < line.length && line[cursor].isInlineWhitespace()) cursor++
        if (cursor >= line.length || line[cursor] != ':') return null
        cursor++
        while (cursor < line.length && line[cursor].isInlineWhitespace()) cursor++
        return HeaderLine(
            name = line.substring(nameStart, nameEnd),
            valueStart = cursor,
            indent = nameStart,
        )
    }

    private fun redactQueryParameters(value: String): String {
        var searchFrom = 0
        var copiedUntil = 0
        var output: StringBuilder? = null

        while (searchFrom < value.length) {
            val questionMark = value.indexOf('?', searchFrom)
            if (questionMark < 0) break
            var cursor = questionMark + 1

            while (cursor < value.length && !value[cursor].isQueryTerminator()) {
                if (value[cursor] == '&' || value[cursor] == ';') {
                    cursor++
                    continue
                }

                val nameStart = cursor
                while (
                    cursor < value.length &&
                    value[cursor] != '=' &&
                    value[cursor] != '&' &&
                    value[cursor] != ';' &&
                    !value[cursor].isQueryTerminator()
                ) {
                    cursor++
                }
                val nameEnd = cursor
                if (nameEnd == nameStart) break

                val sensitive = isDirectSensitiveKey(decodeQueryName(value.substring(nameStart, nameEnd)))
                if (cursor < value.length && value[cursor] == '=') {
                    val queryValueStart = cursor + 1
                    val redactedEnd = queryValueStart + REDACTED.length
                    val queryValueEnd = if (
                        value.startsWith(REDACTED, queryValueStart) &&
                        (redactedEnd >= value.length ||
                            value[redactedEnd] == '&' ||
                            value[redactedEnd] == ';' ||
                            value[redactedEnd].isQueryTerminator())
                    ) {
                        redactedEnd
                    } else {
                        var end = queryValueStart
                        while (
                            end < value.length &&
                            value[end] != '&' &&
                            value[end] != ';' &&
                            !value[end].isQueryTerminator()
                        ) {
                            end++
                        }
                        end
                    }
                    if (sensitive) {
                        if (output == null) output = StringBuilder(value.length)
                        output.append(value, copiedUntil, queryValueStart)
                        output.append(REDACTED)
                        copiedUntil = queryValueEnd
                    }
                    cursor = queryValueEnd
                } else {
                    if (sensitive) {
                        if (output == null) output = StringBuilder(value.length)
                        output.append(value, copiedUntil, nameEnd)
                        output.append('=').append(REDACTED)
                        copiedUntil = nameEnd
                    }
                }

                if (cursor < value.length && (value[cursor] == '&' || value[cursor] == ';')) {
                    cursor++
                } else {
                    break
                }
            }

            searchFrom = maxOf(cursor, questionMark + 1)
        }

        val result = output ?: return value
        result.append(value, copiedUntil, value.length)
        return result.toString()
    }

    private fun redactFreeTextUrls(value: String): String = HTTP_URL_PATTERN.replace(value) { match ->
        val matched = match.value
        var candidateEnd = matched.length
        while (candidateEnd > 0 && matched[candidateEnd - 1] in URL_TRAILING_PUNCTUATION) {
            candidateEnd--
        }
        val candidate = matched.substring(0, candidateEnd)
        val redacted = candidate.toHttpUrlOrNull()?.let(::redactUrl)?.toString()
            ?: return@replace matched
        redacted + matched.substring(candidateEnd)
    }

    private fun redactAuthenticationSchemes(value: String): String = AUTH_SCHEME_PATTERN.replace(value) { match ->
        match.groupValues[1] + match.groupValues[2] + REDACTED
    }

    private fun redactKeyValues(value: String): String {
        var cursor = 0
        var copiedUntil = 0
        var quotedContentEnd = -1
        var output: StringBuilder? = null

        while (cursor < value.length) {
            val key = parseKey(
                value = value,
                start = cursor,
                allowQuotedKey = cursor > quotedContentEnd,
            )
            if (key == null) {
                cursor++
                continue
            }

            var separator = key.endExclusive
            while (separator < value.length && value[separator].isInlineWhitespace()) separator++
            if (separator >= value.length || (value[separator] != ':' && value[separator] != '=')) {
                if (key.quote == null) {
                    cursor = key.endExclusive
                } else {
                    quotedContentEnd = key.endExclusive - 1
                    cursor++
                }
                continue
            }
            val separatorCharacter = value[separator]

            var valueStart = separator + 1
            while (valueStart < value.length && value[valueStart].isInlineWhitespace()) valueStart++
            if (!isSensitiveTextKey(key.decoded)) {
                cursor = key.endExclusive
                continue
            }

            if (output == null) output = StringBuilder(value.length)
            if (valueStart >= value.length) {
                output.append(value, copiedUntil, valueStart)
                output.append(redactionForUnquotedValue(key.quote, separatorCharacter))
                copiedUntil = value.length
                cursor = value.length
                continue
            }

            val quote = value[valueStart].takeIf { it == '\'' || it == '"' }
            if (quote != null) {
                val closingQuote = findClosingQuote(value, valueStart, quote)
                output.append(value, copiedUntil, valueStart + 1)
                output.append(REDACTED)
                if (closingQuote == null) {
                    copiedUntil = value.length
                    cursor = value.length
                } else {
                    output.append(quote)
                    copiedUntil = closingQuote + 1
                    cursor = copiedUntil
                }
                continue
            }

            val valueEnd = when {
                separatorCharacter == ':' && isSensitiveHeaderName(key.decoded) -> {
                    findHeaderValueEnd(value, valueStart)
                }

                value[valueStart] == '{' || value[valueStart] == '[' -> {
                    findCompositeEnd(value, valueStart) ?: value.length
                }

                else -> findUnquotedValueEnd(value, valueStart)
            }
            output.append(value, copiedUntil, valueStart)
            output.append(redactionForUnquotedValue(key.quote, separatorCharacter))
            copiedUntil = valueEnd
            cursor = maxOf(valueEnd, valueStart + 1)
        }

        val result = output ?: return value
        result.append(value, copiedUntil, value.length)
        return result.toString()
    }

    private fun parseKey(
        value: String,
        start: Int,
        allowQuotedKey: Boolean,
    ): ParsedKey? {
        val first = value[start]
        if (first == '\'' || first == '"') {
            if (!allowQuotedKey) return null
            val closingQuote = findClosingQuote(value, start, first) ?: return null
            return ParsedKey(
                decoded = decodeQuotedKey(value, start + 1, closingQuote),
                endExclusive = closingQuote + 1,
                quote = first,
            )
        }

        if (!first.isUnquotedKeyCharacter()) return null
        if (start > 0 && value[start - 1].isUnquotedKeyCharacter()) return null
        var end = start + 1
        while (end < value.length && value[end].isUnquotedKeyCharacter()) end++
        return ParsedKey(
            decoded = value.substring(start, end),
            endExclusive = end,
            quote = null,
        )
    }

    private fun findClosingQuote(value: String, start: Int, quote: Char): Int? {
        var cursor = start + 1
        while (cursor < value.length) {
            when (value[cursor]) {
                '\\' -> cursor += if (cursor + 1 < value.length) 2 else 1
                quote -> return cursor
                else -> cursor++
            }
        }
        return null
    }

    private fun decodeQuotedKey(value: String, start: Int, end: Int): String {
        val output = StringBuilder(end - start)
        var cursor = start
        while (cursor < end) {
            val current = value[cursor]
            if (current != '\\' || cursor + 1 >= end) {
                output.append(current)
                cursor++
                continue
            }

            val escaped = value[cursor + 1]
            if (escaped == 'u' && cursor + 5 < end) {
                val codePoint = value.substring(cursor + 2, cursor + 6).toIntOrNull(16)
                if (codePoint != null) {
                    output.append(codePoint.toChar())
                    cursor += 6
                    continue
                }
            }
            output.append(
                when (escaped) {
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> escaped
                },
            )
            cursor += 2
        }
        return output.toString()
    }

    private fun findCompositeEnd(value: String, start: Int): Int? {
        val expectedClosings = ArrayDeque<Char>()
        expectedClosings.addLast(if (value[start] == '{') '}' else ']')
        var cursor = start + 1

        while (cursor < value.length) {
            when (val current = value[cursor]) {
                '\'', '"' -> {
                    val closingQuote = findClosingQuote(value, cursor, current) ?: return null
                    cursor = closingQuote + 1
                }

                '{' -> {
                    expectedClosings.addLast('}')
                    cursor++
                }

                '[' -> {
                    expectedClosings.addLast(']')
                    cursor++
                }

                '}', ']' -> {
                    if (expectedClosings.isEmpty() || expectedClosings.removeLast() != current) return null
                    cursor++
                    if (expectedClosings.isEmpty()) return cursor
                }

                else -> cursor++
            }
        }
        return null
    }

    private fun findUnquotedValueEnd(value: String, start: Int): Int {
        val redactedEnd = start + REDACTED.length
        if (
            value.startsWith(REDACTED, start) &&
            (redactedEnd >= value.length || value[redactedEnd].isUnquotedValueTerminator())
        ) {
            return redactedEnd
        }
        var cursor = start
        while (cursor < value.length && !value[cursor].isUnquotedValueTerminator()) cursor++
        return cursor
    }

    private fun findHeaderValueEnd(value: String, start: Int): Int {
        var cursor = start
        while (cursor < value.length) {
            when (value[cursor]) {
                '\\' -> cursor += if (cursor + 1 < value.length) 2 else 1
                '\r', '\n', '\'', '"', '}', ']' -> return cursor
                else -> cursor++
            }
        }
        return cursor
    }

    private fun redactionForUnquotedValue(keyQuote: Char?, separator: Char): String = when {
        separator == ':' && keyQuote == '"' -> "\"$REDACTED\""
        separator == ':' && keyQuote == '\'' -> "'$REDACTED'"
        else -> REDACTED
    }

    private fun isSensitiveTextKey(key: String): Boolean =
        isDirectSensitiveKey(key) || containerScope(key, RedactionScope.Config) != null

    private fun isSensitiveHeaderName(name: String): Boolean = isDirectSensitiveKey(name)

    private fun isDirectSensitiveKey(key: String): Boolean {
        val tokens = keyTokens(key)
        if (tokens.isEmpty()) return false
        if (tokens.joinToString(separator = "") in COMPACT_SENSITIVE_KEYS) return true

        val significantTokens = if (tokens.size > 1 && tokens.last() in SECRET_VALUE_SUFFIXES) {
            tokens.dropLast(1)
        } else {
            tokens
        }
        val last = significantTokens.last()
        if (
            last == "password" ||
            last == "passphrase" ||
            last == "token" ||
            last == "secret" ||
            last == "authorization" ||
            last == "cookie" ||
            last == "credential" ||
            last == "credentials"
        ) {
            return true
        }

        return endsWithTokens(significantTokens, listOf("api", "key")) ||
            endsWithTokens(significantTokens, listOf("private", "key")) ||
            endsWithTokens(significantTokens, listOf("secret", "key")) ||
            endsWithTokens(significantTokens, listOf("access", "key")) ||
            endsWithTokens(significantTokens, listOf("host", "fingerprint")) ||
            endsWithTokens(significantTokens, listOf("authorization", "header")) ||
            endsWithTokens(significantTokens, listOf("cookie", "header"))
    }

    private fun keyTokens(key: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }

        key.forEachIndexed { index, character ->
            if (!character.isLetterOrDigit()) {
                flush()
                return@forEachIndexed
            }

            val previous = key.getOrNull(index - 1)
            val next = key.getOrNull(index + 1)
            val camelBoundary = current.isNotEmpty() && character.isUpperCase() &&
                (previous?.isLowerCase() == true ||
                    previous?.isDigit() == true ||
                    previous?.isUpperCase() == true && next?.isLowerCase() == true)
            if (camelBoundary) flush()
            current.append(character.lowercaseChar())
        }
        flush()
        return tokens
    }

    private fun endsWithTokens(tokens: List<String>, suffix: List<String>): Boolean {
        if (tokens.size < suffix.size) return false
        val offset = tokens.size - suffix.size
        return suffix.indices.all { tokens[offset + it] == suffix[it] }
    }

    private fun decodeQueryName(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val output = StringBuilder(value.length)
        var cursor = 0
        while (cursor < value.length) {
            when {
                value[cursor] == '+' -> {
                    output.append(' ')
                    cursor++
                }

                value[cursor] == '%' && cursor + 2 < value.length -> {
                    val high = value[cursor + 1].digitToIntOrNull(16)
                    val low = value[cursor + 2].digitToIntOrNull(16)
                    if (high != null && low != null) {
                        output.append(((high shl 4) or low).toChar())
                        cursor += 3
                    } else {
                        output.append(value[cursor])
                        cursor++
                    }
                }

                else -> {
                    output.append(value[cursor])
                    cursor++
                }
            }
        }
        return output.toString()
    }

    private fun Char.isInlineWhitespace(): Boolean = this == ' ' || this == '\t'

    private fun Char.isHeaderNameCharacter(): Boolean =
        isLetterOrDigit() || this in "!#$%&'*+-.^_`|~"

    private fun Char.isUnquotedKeyCharacter(): Boolean = isLetterOrDigit() || this == '_' || this == '-'

    private fun Char.isQueryTerminator(): Boolean =
        isWhitespace() || this == '#' || this == '\'' || this == '"'

    private fun Char.isUnquotedValueTerminator(): Boolean =
        isWhitespace() || this == ',' || this == '&' || this == ';' || this == '#' ||
            this == '}' || this == ']' || this == ')' || this == '\'' || this == '"'

    private data class HeaderLine(
        val name: String,
        val valueStart: Int,
        val indent: Int,
    )

    private data class ParsedKey(
        val decoded: String,
        val endExclusive: Int,
        val quote: Char?,
    )

    companion object {
        const val REDACTED = "<redacted>"
        const val DEFAULT_MAX_JSON_DEPTH = 64

        private const val PEM_BEGIN_PREFIX = "-----BEGIN "
        private const val PEM_MARKER_SUFFIX = "-----"
        private val HTTP_URL_PATTERN = Regex("""(?i)\bhttps?://[^\s<>\"']+""")
        private val AUTH_SCHEME_PATTERN = Regex(
            """(?i)(?<![A-Za-z0-9_-])(Bearer|Basic|Digest|Token|ApiKey)([ \t]+)([^\s,;]+)""",
        )
        private val URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        private val REDACTED_JSON = JsonPrimitive(REDACTED)
        private val SECRET_VALUE_SUFFIXES = setOf("value", "data", "content", "text", "pem", "material")
        private val COMPACT_SENSITIVE_KEYS = setOf(
            "apikey",
            "authtoken",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "privatekey",
            "secretkey",
            "hostfingerprint",
            "sshpassword",
            "clientsecret",
            "proxyauthorization",
            "setcookie",
            "xapikey",
            "xauthtoken",
        )
    }
}

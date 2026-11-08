package com.github.noahshen.alcyone.context.memory.core.map

import com.github.noahshen.alcyone.context.common.TimeCodec
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Memory Map 的固定 Front Matter/Markdown 渲染与严格反解析。 */
object MemoryMapRenderer {
    private const val OPENING = "---\n"
    private const val FRONT_MATTER_CLOSE = "\n---\n\n"
    private const val OVERVIEW_HEADING = "# Memory Overview\n\n"
    private const val DOMAINS_HEADING = "## Domains\n\n"
    private const val NONE = "_None._\n"
    private const val GENERATED_AT = "generatedAt"
    private const val SOURCE_UPDATED_AT = "sourceUpdatedAt"

    fun render(document: MemoryMapDocument): ByteArray {
        val canonical = MemoryMapValidator.validateDocument(document)
        val markdown =
            buildString {
                append(OPENING)
                append(GENERATED_AT).append(": ").append(TimeCodec.format(canonical.generatedAt)).append('\n')
                append(SOURCE_UPDATED_AT).append(": ")
                append(canonical.sourceUpdatedAt?.let(TimeCodec::format) ?: "null")
                append(FRONT_MATTER_CLOSE)
                append(OVERVIEW_HEADING)
                append(canonical.overview)
                append(MemoryMapValidator.NAVIGATION_BOUNDARY)
                append(canonical.navigation)
                append(MemoryMapValidator.DOMAINS_BOUNDARY)
                if (canonical.domains.isEmpty()) {
                    append(NONE)
                } else {
                    canonical.domains.forEach { domain -> append("- ").append(domain).append('\n') }
                }
            }
        return markdown.toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): MemoryMapDocument = parse(decodeUtf8(bytes))

    fun parse(text: String): MemoryMapDocument {
        if ('\r' in text || !text.startsWith(OPENING)) throw invalid("memory map uses an invalid front matter envelope")
        val close = text.indexOf(FRONT_MATTER_CLOSE, startIndex = OPENING.length)
        if (close < 0) throw invalid("memory map front matter is not closed")
        val frontMatter = text.substring(OPENING.length, close)
        val rest = text.substring(close + FRONT_MATTER_CLOSE.length)
        val times = parseFrontMatter(frontMatter)
        if (!rest.startsWith(OVERVIEW_HEADING)) throw invalid("memory map overview heading is missing")

        val navigationStart = rest.indexOf(MemoryMapValidator.NAVIGATION_BOUNDARY, startIndex = OVERVIEW_HEADING.length)
        if (navigationStart < 0) throw invalid("memory map navigation heading is missing")
        val overview = rest.substring(OVERVIEW_HEADING.length, navigationStart)
        val navigationAndDomains = rest.substring(navigationStart + MemoryMapValidator.NAVIGATION_BOUNDARY.length)
        val domainsStart = navigationAndDomains.indexOf(MemoryMapValidator.DOMAINS_BOUNDARY)
        if (domainsStart < 0) throw invalid("memory map domains heading is missing")
        val navigation = navigationAndDomains.substring(0, domainsStart)
        val domainsBlock = navigationAndDomains.substring(domainsStart + MemoryMapValidator.DOMAINS_BOUNDARY.length)
        val domains = parseDomains(domainsBlock)
        return MemoryMapValidator.validateDocument(
            MemoryMapDocument(
                overview = overview,
                navigation = navigation,
                domains = domains,
                generatedAt = times.first,
                sourceUpdatedAt = times.second,
            ),
        )
    }

    private fun parseFrontMatter(frontMatter: String): Pair<java.time.Instant, java.time.Instant?> {
        val values = LinkedHashMap<String, String>()
        frontMatter.split('\n').forEach { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) throw invalid("memory map front matter line is invalid")
            val key = line.substring(0, separator)
            val value = line.substring(separator + 2)
            if (key !in FRONT_MATTER_KEYS || values.putIfAbsent(key, value) != null) {
                throw invalid("memory map front matter contains a duplicate or unknown key")
            }
        }
        if (values.keys != FRONT_MATTER_KEYS) throw invalid("memory map front matter is missing a required key")
        val generatedAt = parseTime(values.getValue(GENERATED_AT), allowNull = false)!!
        val sourceUpdatedAt = parseTime(values.getValue(SOURCE_UPDATED_AT), allowNull = true)
        return generatedAt to sourceUpdatedAt
    }

    private fun parseTime(
        value: String,
        allowNull: Boolean,
    ): java.time.Instant? {
        if (value == "null" && allowNull) return null
        return try {
            TimeCodec.parse(value)
        } catch (failure: IllegalArgumentException) {
            throw invalid("memory map front matter contains an invalid time")
        }
    }

    private fun parseDomains(block: String): List<String> {
        if (block == NONE) return emptyList()
        if (!block.endsWith('\n')) throw invalid("memory map domains section is incomplete")
        val domains =
            block
                .dropLast(1)
                .split('\n')
                .map { line ->
                    if (!line.startsWith("- ") || line.length <= 2) {
                        throw invalid("memory map domains section is invalid")
                    }
                    line.substring(2)
                }
        if (domains != domains.distinct().sorted()) throw invalid("memory map domains must be unique and sorted")
        return domains
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw invalid("memory map is not valid UTF-8")
        }

    private fun invalid(detail: String): MemoryMapValidationException = MemoryMapValidationException(detail)

    private val FRONT_MATTER_KEYS = setOf(GENERATED_AT, SOURCE_UPDATED_AT)
}

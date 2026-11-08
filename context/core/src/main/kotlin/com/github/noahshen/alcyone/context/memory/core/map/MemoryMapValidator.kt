package com.github.noahshen.alcyone.context.memory.core.map

/** Memory Map 草稿与渲染结构的确定性校验；不设置第二套静默截断长度。 */
object MemoryMapValidator {
    internal const val NAVIGATION_BOUNDARY = "\n\n# Memory Navigation\n\n"
    internal const val DOMAINS_BOUNDARY = "\n\n## Domains\n\n"

    fun validate(
        draft: MemoryMapDraft,
        snapshot: MemoryMapSnapshot,
    ): MemoryMapDraft {
        validateText(draft.overview, draft.navigation)
        val realDomains = snapshot.domains.toSet()
        val selected = LinkedHashSet<String>()
        draft.domains.forEach { domain ->
            if (domain.isBlank() || domain.any { it == '\n' || it == '\r' }) {
                throw MemoryMapValidationException("memory map domains must be non-blank single-line values")
            }
            if (domain !in realDomains) {
                throw MemoryMapValidationException("memory map contains a domain outside the snapshot")
            }
            selected += domain
        }
        return draft.copy(domains = selected.toList())
    }

    fun validateDocument(document: MemoryMapDocument): MemoryMapDocument {
        validateText(document.overview, document.navigation)
        val canonicalDomains = document.domains.distinct().sorted()
        if (canonicalDomains != document.domains ||
            canonicalDomains.any { domain -> domain.isBlank() || domain.any { it == '\n' || it == '\r' } }
        ) {
            throw MemoryMapValidationException("memory map document domains must be unique, sorted and single-line")
        }
        return document.copy(domains = canonicalDomains)
    }

    private fun validateText(
        overview: String,
        navigation: String,
    ) {
        if (overview.isBlank()) throw MemoryMapValidationException("memory map overview must not be blank")
        if (navigation.isBlank()) throw MemoryMapValidationException("memory map navigation must not be blank")
        if (overview.any { it == '\r' } || navigation.any { it == '\r' }) {
            throw MemoryMapValidationException("memory map text must use LF line endings")
        }
        if (overview.startsWith('\n') || overview.endsWith('\n')) {
            throw MemoryMapValidationException("memory map overview must not start or end with a newline")
        }
        if (navigation.startsWith('\n') || navigation.endsWith('\n')) {
            throw MemoryMapValidationException("memory map navigation must not start or end with a newline")
        }
        if (NAVIGATION_BOUNDARY in overview || DOMAINS_BOUNDARY in overview) {
            throw MemoryMapValidationException("memory map overview contains a reserved section boundary")
        }
        if (DOMAINS_BOUNDARY in navigation) {
            throw MemoryMapValidationException("memory map navigation contains a reserved section boundary")
        }
    }
}

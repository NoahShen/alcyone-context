package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.runtime.retrieval.MemorySearchJsonObjectReader

/** Raw/Organized scanner 共用的 `candidate_ids` 严格解析。 */
internal class JsonDreamingEvidenceCandidateParser {
    fun parse(content: String): List<String> =
        MemorySearchJsonObjectReader.readStringArray(
            content = content,
            field = CANDIDATE_IDS,
            errorMessage = INVALID_RESPONSE_MESSAGE,
        )

    private companion object {
        const val CANDIDATE_IDS = "candidate_ids"
        const val INVALID_RESPONSE_MESSAGE = "invalid dreaming evidence response"
    }
}

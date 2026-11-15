package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Serializable output for an offline evaluation task. */
@JvmInline
value class EvalOutput private constructor(
    val json: JsonElement,
) {
    fun toJsonString(): String = Json.encodeToString(JsonElement.serializer(), json)

    companion object {
        fun of(json: JsonElement): EvalOutput = EvalOutput(json)

        fun encode(output: Any): String =
            when (output) {
                is EvalOutput -> output.toJsonString()
                is JsonElement -> Json.encodeToString(JsonElement.serializer(), output)
                is String -> Json.encodeToString(kotlinx.serialization.serializer<String>(), output)
                is Number -> Json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(output))
                is Boolean -> Json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(output))
                is Map<*, *> ->
                    Json.encodeToString(
                        JsonElement.serializer(),
                        kotlinx.serialization.json.JsonObject(
                            output.entries.associate { (key, value) ->
                                require(key is String) { "evaluation output keys must be strings" }
                                requireNotNull(value) { "evaluation output must not contain null" }
                                key to Json.parseToJsonElement(encode(value))
                            },
                        ),
                    )
                is List<*> ->
                    Json.encodeToString(
                        JsonElement.serializer(),
                        kotlinx.serialization.json.JsonArray(
                            output.map { element ->
                                requireNotNull(element) { "evaluation output must not contain null" }
                                Json.parseToJsonElement(encode(element))
                            },
                        ),
                    )
                else -> throw IllegalArgumentException("unsupported evaluation output type: ${output::class.qualifiedName}")
            }
    }
}

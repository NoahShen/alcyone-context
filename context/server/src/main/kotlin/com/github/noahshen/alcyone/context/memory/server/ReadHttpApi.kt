package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ContextResourceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextResourceException
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val readJson =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

@Serializable
private data class SearchRequest(
    val query: String,
    val domains: List<String>? = null,
    val limit: Int = 10,
)

/** B1 Read/Core 路由；成功和错误都只经公共 SDK 与安全 DTO。 */
internal fun Application.readRoutes(
    context: AlcyoneContext,
    auth: ServerAuthRegistry,
    limits: ServerLimits,
) {
    routing {
        get("/v1/context/bootstrap") {
            if (!requireOperation(call, auth, CredentialKind.READ)) return@get
            withSdkDeadline(call, limits) {
                val response = BootstrapResponse.from(context.bootstrap())
                call.respondJson(readJson.encodeToString(response))
            }
        }

        post("/v1/memories/search") {
            if (!requireOperation(call, auth, CredentialKind.READ)) return@post
            withSdkDeadline(call, limits) {
                val request = readSearchRequest(call, limits.maxSearchBodyBytes)
                val memories = context.searchMemory(request.query, request.domains, request.limit)
                call.respondJson(readJson.encodeToString(memories.map(OrganizedMemoryResponse::from)))
            }
        }

        get("/v1/memories/{id}") {
            if (!requireOperation(call, auth, CredentialKind.READ)) return@get
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) return@get call.respondError(HttpStatusCode.BadRequest, "INVALID_ID")
            withSdkDeadline(call, limits) {
                val memory = context.getMemory(id)
                if (memory == null) {
                    call.respondError(HttpStatusCode.NotFound, "MEMORY_NOT_FOUND")
                } else {
                    call.respondJson(readJson.encodeToString(OrganizedMemoryResponse.from(memory)))
                }
            }
        }

        get("/v1/raw-memories/{id}") {
            if (!requireOperation(call, auth, CredentialKind.READ)) return@get
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) return@get call.respondError(HttpStatusCode.BadRequest, "INVALID_ID")
            withSdkDeadline(call, limits) {
                val memory = context.getRawMemory(id)
                if (memory == null) {
                    call.respondError(HttpStatusCode.NotFound, "MEMORY_NOT_FOUND")
                } else {
                    call.respondJson(readJson.encodeToString(RawMemoryResponse.from(memory)))
                }
            }
        }

        get("/v1/resources/meta") {
            if (!requireOperation(call, auth, CredentialKind.READ)) return@get
            val uri = resourceUri(call)
            if (uri == null) return@get call.respondError(HttpStatusCode.BadRequest, "INVALID_URI")
            withSdkDeadline(call, limits) {
                call.respondJson(readJson.encodeToString(ResourceMetadataResponse.from(context.loadMeta(uri))))
            }
        }

        get("/v1/resources/content") {
            if (!requireOperation(call, auth, CredentialKind.READ)) return@get
            val uri = resourceUri(call)
            if (uri == null) return@get call.respondError(HttpStatusCode.BadRequest, "INVALID_URI")
            streamResource(call, context, uri, limits)
        }
    }
}

private fun resourceUri(call: ApplicationCall): String? {
    val values = call.request.queryParameters.getAll("uri") ?: return null
    if (values.size != 1 || values.single().isBlank()) return null
    return values.single()
}

private suspend fun readSearchRequest(
    call: ApplicationCall,
    maxBytes: Int,
): SearchRequest {
    val declared = call.request.headers[HttpHeaders.ContentLength]
    if (declared != null) {
        val length = declared.toLongOrNull() ?: throw ApiRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
        if (length < 0) throw ApiRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
        if (length > maxBytes) throw ApiRequestFailure(HttpStatusCode.PayloadTooLarge, "REQUEST_TOO_LARGE")
    }
    val bytes =
        readBodyUpTo(call.receiveChannel(), maxBytes)
            ?: throw ApiRequestFailure(HttpStatusCode.PayloadTooLarge, "REQUEST_TOO_LARGE")
    val text =
        decodeStrictUtf8(bytes)
            ?: throw ApiRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    if (hasDuplicateJsonKeys(text)) throw ApiRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    return try {
        readJson.decodeFromString<SearchRequest>(text)
    } catch (failure: IllegalArgumentException) {
        throw ApiRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    }
}

private suspend fun ApplicationCall.respondJson(json: String) {
    respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}

private suspend fun withSdkDeadline(
    call: ApplicationCall,
    limits: ServerLimits,
    block: suspend () -> Unit,
) {
    try {
        withServerRequestDeadline(limits.requestTimeoutMillis, block)
    } catch (failure: ServerRequestDeadlineException) {
        if (call.response.isCommitted) throw failure
        call.respondError(HttpStatusCode.GatewayTimeout, "REQUEST_TIMEOUT")
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: ApiRequestFailure) {
        if (call.response.isCommitted) throw failure
        call.respondError(failure.status, failure.code)
    } catch (failure: ContextResourceException) {
        if (call.response.isCommitted) throw failure
        val (status, code) = resourceFailure(failure.errorCode)
        call.respondError(status, code)
    } catch (failure: ContextRetrievalException) {
        if (call.response.isCommitted) throw failure
        val (status, code) = retrievalFailure(failure.errorCode)
        call.respondError(status, code)
    } catch (failure: ContextLifecycleException) {
        if (call.response.isCommitted) throw failure
        call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
    } catch (failure: ContextPersistenceException) {
        if (call.response.isCommitted) throw failure
        val status =
            if (failure.errorCode == com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE) {
                HttpStatusCode.ServiceUnavailable
            } else {
                HttpStatusCode.InternalServerError
            }
        call.respondError(status, if (status == HttpStatusCode.InternalServerError) "INTERNAL_ERROR" else "SERVICE_UNAVAILABLE")
    } catch (failure: Exception) {
        if (call.response.isCommitted) throw failure
        call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
    }
}

private suspend fun streamResource(
    call: ApplicationCall,
    context: AlcyoneContext,
    uri: String,
    limits: ServerLimits,
) {
    var responseStarted = false
    try {
        withServerRequestDeadline(limits.requestTimeoutMillis) {
            val metadata = context.loadMeta(uri)
            val configuredLimit = limits.maxResourceBytes
            val knownSize = metadata.sizeBytes
            if (configuredLimit != null && knownSize != null && knownSize > configuredLimit) {
                throw ApiRequestFailure(HttpStatusCode.PayloadTooLarge, "RESOURCE_LIMIT_EXCEEDED")
            }

            coroutineScope {
                val channel = Channel<ByteArray>(Channel.RENDEZVOUS)
                val producer = launch { produceChunks(context.readStream(uri), channel) }
                try {
                    val first = receiveChunk(channel)
                    var total = 0L
                    if (first != null) {
                        configuredLimit?.let {
                            if (first.size.toLong() > it) {
                                throw ApiRequestFailure(HttpStatusCode.PayloadTooLarge, "RESOURCE_LIMIT_EXCEEDED")
                            }
                        }
                        total = first.size.toLong()
                    }

                    responseStarted = true
                    call.respondBytesWriter(ContentType.Application.OctetStream, HttpStatusCode.OK) {
                        try {
                            if (first != null) writeFully(first)
                            while (true) {
                                val chunk = receiveChunk(channel) ?: break
                                configuredLimit?.let {
                                    if (chunk.size.toLong() > it - total) throw ResourceStreamLimitException()
                                }
                                total += chunk.size
                                writeFully(chunk)
                            }
                            flush()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            throw failure
                        }
                    }
                } finally {
                    producer.cancel()
                    channel.cancel()
                    withContext(NonCancellable) { producer.join() }
                }
            }
        }
    } catch (failure: ServerRequestDeadlineException) {
        if (responseStarted) throw AbortedResourceStreamException()
        call.respondError(HttpStatusCode.GatewayTimeout, "REQUEST_TIMEOUT")
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: ApiRequestFailure) {
        if (responseStarted) throw AbortedResourceStreamException()
        call.respondError(failure.status, failure.code)
    } catch (failure: ContextResourceException) {
        if (responseStarted) throw AbortedResourceStreamException()
        val (status, code) = resourceFailure(failure.errorCode)
        call.respondError(status, code)
    } catch (failure: ContextLifecycleException) {
        if (responseStarted) throw AbortedResourceStreamException()
        call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
    } catch (failure: ContextPersistenceException) {
        if (responseStarted || call.response.isCommitted) throw AbortedResourceStreamException()
        val status =
            if (failure.errorCode == com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE) {
                HttpStatusCode.ServiceUnavailable
            } else {
                HttpStatusCode.InternalServerError
            }
        call.respondError(status, if (status == HttpStatusCode.InternalServerError) "INTERNAL_ERROR" else "SERVICE_UNAVAILABLE")
    } catch (failure: Exception) {
        if (responseStarted) throw AbortedResourceStreamException()
        call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
    }
}

private suspend fun produceChunks(
    flow: Flow<ByteArray>,
    channel: Channel<ByteArray>,
) {
    try {
        flow.collect { chunk -> if (chunk.isNotEmpty()) channel.send(chunk) }
        channel.close()
    } catch (failure: CancellationException) {
        channel.cancel(failure)
        throw failure
    } catch (failure: Throwable) {
        channel.close(failure)
    }
}

private suspend fun receiveChunk(channel: Channel<ByteArray>): ByteArray? {
    val result = channel.receiveCatching()
    val failure = result.exceptionOrNull()
    if (failure != null) throw failure
    return result.getOrNull()
}

private fun resourceFailure(code: ContextResourceErrorCode): Pair<HttpStatusCode, String> =
    when (code) {
        ContextResourceErrorCode.INVALID_URI -> HttpStatusCode.BadRequest to "INVALID_URI"
        ContextResourceErrorCode.TYPE_MISMATCH -> HttpStatusCode.BadRequest to "RESOURCE_TYPE_MISMATCH"
        ContextResourceErrorCode.OUT_OF_SCOPE -> HttpStatusCode.Forbidden to "OUT_OF_SCOPE"
        ContextResourceErrorCode.NOT_FOUND -> HttpStatusCode.NotFound to "RESOURCE_NOT_FOUND"
        ContextResourceErrorCode.LIMIT_EXCEEDED -> HttpStatusCode.PayloadTooLarge to "RESOURCE_LIMIT_EXCEEDED"
        ContextResourceErrorCode.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable to "RESOURCE_UNAVAILABLE"
    }

private fun retrievalFailure(code: ContextRetrievalErrorCode): Pair<HttpStatusCode, String> =
    when (code) {
        ContextRetrievalErrorCode.INVALID_REQUEST -> HttpStatusCode.BadRequest to "INVALID_REQUEST"
        ContextRetrievalErrorCode.CONFIGURATION,
        ContextRetrievalErrorCode.UNAVAILABLE,
        -> HttpStatusCode.ServiceUnavailable to "RETRIEVAL_UNAVAILABLE"
    }

private class ApiRequestFailure(
    val status: HttpStatusCode,
    val code: String,
) : RuntimeException(code)

private class ResourceStreamLimitException : RuntimeException()

/** A committed response must abort without propagating backend messages or a cause into platform logs. */
private class AbortedResourceStreamException : RuntimeException("resource stream aborted")

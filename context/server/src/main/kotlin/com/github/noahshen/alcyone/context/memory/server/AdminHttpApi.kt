package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextBackupErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextBackupException
import com.github.noahshen.alcyone.context.memory.api.ContextBackupFailureStage
import com.github.noahshen.alcyone.context.memory.api.ContextDreamingErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextDreamingException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.runtime.backup.failureStage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val adminJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

/** ADMIN-only process facts/manual runs and unauthenticated liveness/readiness probes. */
internal fun Application.adminAndHealthRoutes(
    context: AlcyoneContext,
    auth: ServerAuthRegistry,
    limits: ServerLimits,
) {
    routing {
        if (auth.supports(CredentialKind.ADMIN)) {
            get("/v1/admin/status") {
                if (!requireOperation(call, auth, CredentialKind.ADMIN)) return@get
                withAdminRequest(call, limits) {
                    val response = AdminStatusResponse.from(context.getAdminStatus())
                    call.respondJson(adminJson.encodeToString(response))
                }
            }
            post("/v1/admin/dreaming") {
                if (!requireOperation(call, auth, CredentialKind.ADMIN)) return@post
                withAdminRequest(call, limits) {
                    call.requireEmptyAdminBody(limits.maxSearchBodyBytes)
                    val response = DreamingRunResponse.from(context.runDreaming())
                    call.respondJson(adminJson.encodeToString(response))
                }
            }
            post("/v1/admin/backup") {
                if (!requireOperation(call, auth, CredentialKind.ADMIN)) return@post
                withAdminRequest(call, limits, classifyBackup = true) {
                    call.requireEmptyAdminBody(limits.maxSearchBodyBytes)
                    val response = BackupRunResponse.from(context.backupNow())
                    call.respondJson(adminJson.encodeToString(response))
                }
            }
        }
        get("/health/live") {
            call.respondText("""{"status":"live"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/health/ready") {
            val ready =
                try {
                    withServerRequestDeadline(limits.readyTimeoutMillis) { context.checkReady() }
                } catch (failure: ServerRequestDeadlineException) {
                    false
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    false
                }
            if (ready) {
                call.respondText("""{"status":"ready"}""", ContentType.Application.Json, HttpStatusCode.OK)
            } else {
                call.respondError(HttpStatusCode.ServiceUnavailable, "NOT_READY")
            }
        }
    }
}

private suspend fun ApplicationCall.requireEmptyAdminBody(maxBytes: Int) {
    val declared = request.headers[HttpHeaders.ContentLength]
    if (declared != null) {
        val length = declared.toLongOrNull() ?: throw AdminRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
        if (length < 0) throw AdminRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
        if (length > maxBytes) throw AdminRequestFailure(HttpStatusCode.PayloadTooLarge, "REQUEST_TOO_LARGE")
    }
    val bytes =
        readBodyUpTo(receiveChannel(), maxBytes)
            ?: throw AdminRequestFailure(HttpStatusCode.PayloadTooLarge, "REQUEST_TOO_LARGE")
    if (bytes.isEmpty()) return
    val text = decodeStrictUtf8(bytes) ?: throw AdminRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    if (hasDuplicateJsonKeys(text)) throw AdminRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    try {
        val body = adminJson.decodeFromString<JsonObject>(text)
        if (body.isNotEmpty()) throw AdminRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    } catch (failure: AdminRequestFailure) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw AdminRequestFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST")
    }
}

private suspend fun withAdminRequest(
    call: ApplicationCall,
    limits: ServerLimits,
    classifyBackup: Boolean = false,
    block: suspend () -> Unit,
) {
    try {
        withServerRequestDeadline(limits.requestTimeoutMillis, block)
    } catch (failure: ServerRequestDeadlineException) {
        if (call.response.isCommitted) throw failure
        call.respondError(HttpStatusCode.GatewayTimeout, "REQUEST_TIMEOUT")
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: AdminRequestFailure) {
        if (call.response.isCommitted) throw failure
        call.respondError(failure.status, failure.code)
    } catch (failure: ContextDreamingException) {
        val status =
            if (failure.errorCode ==
                ContextDreamingErrorCode.UNAVAILABLE
            ) {
                HttpStatusCode.ServiceUnavailable
            } else {
                HttpStatusCode.Conflict
            }
        val code = if (failure.errorCode == ContextDreamingErrorCode.UNAVAILABLE) "DREAMING_UNAVAILABLE" else "DREAMING_ALREADY_RUNNING"
        call.respondError(status, code)
    } catch (failure: DreamingRunException) {
        call.respondError(HttpStatusCode.InternalServerError, "DREAMING_${failure.stage.name}")
    } catch (failure: ContextBackupException) {
        when (failure.code) {
            ContextBackupErrorCode.UNAVAILABLE ->
                call.respondError(HttpStatusCode.ServiceUnavailable, "BACKUP_UNAVAILABLE")
            ContextBackupErrorCode.ALREADY_RUNNING ->
                call.respondError(HttpStatusCode.Conflict, "BACKUP_ALREADY_RUNNING")
            ContextBackupErrorCode.CLOSED,
            ContextBackupErrorCode.NOT_STARTED,
            -> call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
            ContextBackupErrorCode.QUICK_CHECK_FAILED ->
                call.respondError(HttpStatusCode.InternalServerError, "BACKUP_QUICK_CHECK_FAILED")
            ContextBackupErrorCode.PUBLISH_FAILED ->
                call.respondError(HttpStatusCode.InternalServerError, "BACKUP_PUBLISH_FAILED")
        }
    } catch (failure: ContextLifecycleException) {
        call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
    } catch (failure: ContextPersistenceException) {
        call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
    } catch (failure: Exception) {
        val code =
            when (if (classifyBackup) failureStage(failure) else null) {
                ContextBackupFailureStage.SNAPSHOT -> "BACKUP_SNAPSHOT_FAILED"
                ContextBackupFailureStage.PRE_CHECK -> "BACKUP_PRE_CHECK_FAILED"
                ContextBackupFailureStage.POST_CHECK -> "BACKUP_POST_CHECK_FAILED"
                ContextBackupFailureStage.PUBLISH -> "BACKUP_PUBLISH_FAILED"
                null -> "INTERNAL_ERROR"
            }
        call.respondError(HttpStatusCode.InternalServerError, code)
    }
}

private suspend fun ApplicationCall.respondJson(json: String) {
    respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}

private class AdminRequestFailure(
    val status: HttpStatusCode,
    val code: String,
) : RuntimeException()

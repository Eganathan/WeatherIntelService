package dev.eknath.api

import dev.eknath.config.AppConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

suspend fun ApplicationCall.requireApiKey(): Boolean {
    val key = request.headers["X-API-Key"]
    if (key == null || key != AppConfig.apiKey) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Invalid or missing X-API-Key header"))
        return false
    }
    return true
}

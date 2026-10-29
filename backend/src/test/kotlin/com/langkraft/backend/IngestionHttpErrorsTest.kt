package com.langkraft.backend

import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.server.request.receive
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import com.langkraft.domain.model.RegisterRequest

class IngestionHttpErrorsTest {
    @Test fun invalidJsonAndParametersReturn400AndUnexpectedFailuresHideDetails() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { configureBackendExceptions() }
            routing {
                post("/body") { call.receive<RegisterRequest>() }
                get("/parameter") { require(false) { "private validation detail" } }
                get("/failure") { error("private database path or token") }
            }
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/body") {
            contentType(ContentType.Application.Json); setBody("{broken")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/parameter").status)
        val failure = client.get("/failure")
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertFalse(failure.bodyAsText().contains("private"))
    }
    @Test fun overloadReturns503WithRetryAfterAndInvalidUrlReturns400() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(StatusPages) { configureBackendExceptions() }
            routing {
                get("/busy") { throw IngestionBusyException() }
                get("/media-full") { throw MediaCapacityException() }
                get("/invalid") { SourceUrlValidator.validate("https://example.com/video") }
                get("/ai-timeout") { throw AiTimeoutException() }
                get("/ai-unavailable") { throw AiProviderUnavailableException() }
                get("/ai-busy") { throw AiBusyException() }
                get("/ai-invalid") { throw AiException("Invalid AI output") }
            }
        }
        val busy = client.get("/busy")
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertEquals("5", busy.headers[HttpHeaders.RetryAfter])
        val full = client.get("/media-full")
        assertEquals(HttpStatusCode.ServiceUnavailable, full.status)
        assertEquals("5", full.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.BadRequest, client.get("/invalid").status)
        assertEquals(HttpStatusCode.GatewayTimeout, client.get("/ai-timeout").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ai-unavailable").status)
        val aiBusy = client.get("/ai-busy")
        assertEquals(HttpStatusCode.ServiceUnavailable, aiBusy.status)
        assertEquals("2", aiBusy.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.BadGateway, client.get("/ai-invalid").status)
    }
}

package com.langkraft.backend

import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IngestionHttpErrorsTest {
    @Test fun overloadReturns503WithRetryAfterAndInvalidUrlReturns400() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(StatusPages) { configureBackendExceptions() }
            routing {
                get("/busy") { throw IngestionBusyException() }
                get("/invalid") { SourceUrlValidator.validate("https://example.com/video") }
            }
        }
        val busy = client.get("/busy")
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertEquals("5", busy.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.BadRequest, client.get("/invalid").status)
    }
}

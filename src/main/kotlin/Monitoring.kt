package me.ezar.anemon

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") && call.response.status() != HttpStatusCode.NotFound  }
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondText("This is a development backend for a research project. There's 0 public vulnerability for it because It's custom made using ktor. Every request is logged, and you won't get anything useful here.",
                status = HttpStatusCode.NotFound)
        }
    }
}

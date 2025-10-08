package me.ezar.anemon.routes

import clients.GeocodingClient
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.ezar.anemon.routes.dto.ReverseGeocodingRequest
import me.ezar.anemon.routes.dto.ReverseGeocodingResponse
import org.jetbrains.exposed.sql.exposedLogger

fun Route.geocoding(geocodingClient: GeocodingClient) {
    route("/geocoding") {
        post("/reverse") {
            val dto = call.receive<ReverseGeocodingRequest>()
            try {
                val alamat = geocodingClient.doReverseGeocodeRequest(dto.latitude, dto.longitude)
                call.respond(ReverseGeocodingResponse(alamat))
            } catch (e: Exception) {
                exposedLogger.error("Error during reverse geocoding", e)
                exposedLogger.error("Request data: $dto")
                exposedLogger.error("Response: ${e.message}")
            }
        }
    }
}
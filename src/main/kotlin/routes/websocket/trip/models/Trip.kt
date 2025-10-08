package me.ezar.anemon.routes.websocket.trip.models

import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import matching.models.Point
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.utils.ktorLogger
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TripDetails(
    val pickupPoint: Point,
    val destinationPoint: Point,
    var status: PassengerTripStatus,
    val distanceMeters: Int,
    val tariffRupiah: Long
)

@Serializable
data class TripState(
    val tripId: String,
    val driver: UserProfile,
    val passengers: MutableMap<UserProfile, TripDetails>,
    var status: TripStatus,
    val availableSlots: Int,
    val cancellationRequesters: MutableSet<UserProfile> = mutableSetOf()
)

data class Trip(
    val state: TripState,
    val connections: ConcurrentHashMap<UserProfile, DefaultWebSocketSession> = ConcurrentHashMap()
) {
    suspend fun broadcast(message: Frame) {
        connections.values.forEach { session ->
            try {
                session.send(message.copy())
            } catch (e: Exception) {
                ktorLogger.info("Failed to send message to a participant: ${e.message}")
            }
        }
    }
}
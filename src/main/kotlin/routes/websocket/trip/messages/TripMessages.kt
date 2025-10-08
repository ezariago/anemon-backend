package me.ezar.anemon.routes.websocket.trip.messages

import matching.models.Point
import me.ezar.anemon.configuredJson
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.routes.websocket.trip.enum.TripAction
import me.ezar.anemon.routes.websocket.trip.models.TripState
import me.ezar.anemon.utils.fromBase64
import me.ezar.anemon.utils.toBase64

// --- Client -> Server Messages ---

data class JoinTripMessage(val tripId: String) {
    companion object {
        fun fromRawMessage(rawMessage: String): JoinTripMessage {
            val parts = rawMessage.split(" ")
            if (parts.size < 2) throw IllegalArgumentException("Invalid JoinTripMessage format")
            return JoinTripMessage(parts[1])
        }
    }
}

data class UpdateLocationMessage(val location: Point) {
    constructor(formattedMessage: String) : this(
        location = configuredJson.decodeFromString(Point.serializer(), formattedMessage.split(" ")[1])
    )
}

data class PassengerActionMessage(val passenger: UserProfile) {
    constructor(formattedMessage: String) : this(
        passenger = configuredJson.decodeFromString(
            UserProfile.serializer(),
            formattedMessage.split(" ")[1].fromBase64()
        )
    )
}

// --- Server -> Client Messages ---
fun createPolylineUpdateMessage(encodedPolyline: String): String {
    val action = TripAction.POLYLINE_UPDATE
    return "$action ${encodedPolyline.toBase64()}"
}

fun createTripStateUpdateMessage(tripState: TripState): String {
    val action = TripAction.TRIP_STATE_UPDATE
    val stateJson = configuredJson.encodeToString(TripState.serializer(), tripState)
    return "$action ${stateJson.toBase64()}"
}

fun createLocationBroadcastMessage(sender: UserProfile, location: Point): String {
    val action = TripAction.LOCATION_BROADCAST
    val profileJson = configuredJson.encodeToString(UserProfile.serializer(), sender).toBase64()
    val locationJson = configuredJson.encodeToString(Point.serializer(), location).toBase64()
    return "$action ${profileJson} $locationJson"
}

fun createErrorMessage(details: String): String {
    val action = TripAction.ERROR
    return "$action $details"
}

fun createCancelRequestBroadcastMessage(requester: UserProfile): String {
    val action = TripAction.CANCEL_REQUEST_BROADCAST
    val profileJson = configuredJson.encodeToString(UserProfile.serializer(), requester).toBase64()
    return "$action $profileJson"
}
package me.ezar.anemon.routes.websocket.trip.enum

enum class TripAction {
    // Client -> Server
    JOIN_TRIP,
    UPDATE_LOCATION,
    PICKUP_PASSENGER,
    DROPOFF_PASSENGER,

    UPDATE_CANCELLATION,

    // Server -> Client
    TRIP_STATE_UPDATE,
    POLYLINE_UPDATE,
    LOCATION_BROADCAST,
    ERROR,

    CANCEL_REQUEST_BROADCAST,

}
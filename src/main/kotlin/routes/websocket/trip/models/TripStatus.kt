package me.ezar.anemon.routes.websocket.trip.models

enum class TripStatus {
    AWAITING_PARTICIPANTS,
    EN_ROUTE_TO_PICKUP,
    RECONNECTING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
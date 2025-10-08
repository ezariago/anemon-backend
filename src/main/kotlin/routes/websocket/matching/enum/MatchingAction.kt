package me.ezar.anemon.routes.websocket.matching.enum

enum class MatchingAction {
    REGISTER_DRIVER,
    REGISTER_PASSENGER,
    TRIP_REQUEST,
    TRIP_ACCEPT,
    MATCH,
    MATCH_CANCEL,
    STOP_MATCHING,
    UPDATE_DRIVER_ROUTE
}
package me.ezar.anemon.routes.websocket.matching.messages

import matching.models.Point
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction
import me.ezar.anemon.routes.websocket.matching.models.LineSegment

data class UpdateDriverRouteMessage(
    val route: List<LineSegment>
) : IWebsocketMessage {
    override val action = MatchingAction.UPDATE_DRIVER_ROUTE
    override fun toWebsocketMessageString(): String {
        return "$action ${route.joinToString(" ") { "${it.start.latitude},${it.start.longitude}:${it.end.latitude},${it.end.longitude}" }}"
    }

    constructor(formattedString: String) : this(
        route = formattedString.split(" ").drop(1).map {
            val (startStr, endStr) = it.split(":")
            val (startLat, startLng) = startStr.split(",").map(String::toDouble)
            val (endLat, endLng) = endStr.split(",").map(String::toDouble)
            LineSegment(
                start = Point(startLat, startLng),
                end = Point(endLat, endLng)
            )
        }
    )
}
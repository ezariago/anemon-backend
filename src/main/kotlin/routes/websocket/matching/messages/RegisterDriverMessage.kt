package me.ezar.anemon.routes.websocket.matching.messages

import matching.models.Point
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction
import me.ezar.anemon.routes.websocket.matching.models.LineSegment

data class RegisterDriverMessage(
    val availableSlots: Int,
    val route: List<LineSegment>,
) : IWebsocketMessage {
    override val action = MatchingAction.REGISTER_DRIVER

    override fun toWebsocketMessageString(): String {
        return "$action $availableSlots ${route.joinToString(" ") { "${it.start.latitude},${it.start.longitude}:${it.end.latitude},${it.end.longitude}" }}"
    }

    constructor(formattedMessage: String) : this(
        availableSlots = formattedMessage.split(" ")[1].toInt(),
        route = formattedMessage.split(" ").drop(2).map { segment ->
            val points = segment.split(":")
            if (points.size != 2) throw IllegalArgumentException("Invalid route segment format. Expected 'startLat,startLng:endLat,endLng'.")
            LineSegment(
                start = Point(points[0].split(",")[0].toDouble(), points[0].split(",")[1].toDouble()),
                end = Point(points[1].split(",")[0].toDouble(), points[1].split(",")[1].toDouble())
            )
        }
    )
}
package me.ezar.anemon.routes.websocket.matching

import matching.models.Point
import me.ezar.anemon.routes.websocket.matching.models.LineSegment
import kotlin.math.pow
import kotlin.math.sqrt

fun distanceToSegment(p: Point, segment: LineSegment): Double {
    val a = segment.start
    val b = segment.end
    val segmentLengthSq = (b.latitude - a.latitude).pow(2) + (b.longitude - a.longitude).pow(2)

    if (segmentLengthSq == 0.0) {
        return sqrt((p.latitude - a.latitude).pow(2) + (p.longitude - a.longitude).pow(2))
    }

    val t =
        ((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / segmentLengthSq
    val clampedT = t.coerceIn(0.0, 1.0)

    val closestPointX = a.latitude + clampedT * (b.latitude - a.latitude)
    val closestPointY = a.longitude + clampedT * (b.longitude - a.longitude)

    val dx = p.latitude - closestPointX
    val dy = p.longitude - closestPointY

    return sqrt(dx.pow(2) + dy.pow(2))
}
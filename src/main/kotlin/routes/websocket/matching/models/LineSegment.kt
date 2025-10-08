package me.ezar.anemon.routes.websocket.matching.models

import kotlinx.serialization.Serializable
import matching.models.Point

@Serializable
data class LineSegment(val start: Point, val end: Point)
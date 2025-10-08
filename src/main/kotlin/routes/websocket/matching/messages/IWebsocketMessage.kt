package me.ezar.anemon.routes.websocket.matching.messages

import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction

interface IWebsocketMessage {
    val action: MatchingAction
    fun toWebsocketMessageString(): String
}
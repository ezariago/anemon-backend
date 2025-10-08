package me.ezar.anemon.routes.websocket.matching.messages

import me.ezar.anemon.configuredJson
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction
import me.ezar.anemon.utils.toBase64

data class MatchCancelMessage(
    val passengerProfile: UserProfile,
) : IWebsocketMessage {
    override val action = MatchingAction.MATCH_CANCEL

    override fun toWebsocketMessageString(): String {
        return "$action ${configuredJson.encodeToString(passengerProfile).toBase64()}"
    }

}
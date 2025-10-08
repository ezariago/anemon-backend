package me.ezar.anemon.routes.websocket.matching.messages

import me.ezar.anemon.configuredJson
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction
import me.ezar.anemon.utils.toBase64

data class MatchFoundMessage(val userProfile: UserProfile, val tripId: String) : IWebsocketMessage {
    override val action = MatchingAction.MATCH

    override fun toWebsocketMessageString(): String {
        // Format: MATCH <tripId> <userProfileJsonBase64>
        return "$action $tripId ${configuredJson.encodeToString(userProfile).toBase64()}"
    }
}
package me.ezar.anemon.routes.websocket.matching.messages

import me.ezar.anemon.configuredJson
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction
import me.ezar.anemon.utils.fromBase64
import me.ezar.anemon.utils.toBase64

data class TripAcceptMessage(
    val passengerProfile: UserProfile,
) : IWebsocketMessage {
    override val action = MatchingAction.TRIP_ACCEPT

    override fun toWebsocketMessageString(): String {
        return "$action ${configuredJson.encodeToString(passengerProfile).toBase64()}"
    }

    constructor(formattedMessage: String) : this(
        passengerProfile = configuredJson.decodeFromString(
            UserProfile.serializer(),
            formattedMessage.split(" ")[1].fromBase64()
        )
    )
}
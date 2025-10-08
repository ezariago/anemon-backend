package me.ezar.anemon.routes.websocket.matching.messages

import matching.models.Point
import me.ezar.anemon.configuredJson
import me.ezar.anemon.routes.dto.AccountVehiclePreference
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction

data class RegisterPassengerMessage(
    val vehicle: AccountVehiclePreference,
    val pickupPoint: Point,
    val destinationPoint: Point
) : IWebsocketMessage {
    override val action = MatchingAction.REGISTER_PASSENGER
    override fun toWebsocketMessageString(): String {
        return "$action $vehicle ${configuredJson.encodeToString(pickupPoint)} ${
            configuredJson.encodeToString(
                destinationPoint
            )
        }"
    }

    constructor(formattedMessage: String) : this(
        vehicle = AccountVehiclePreference.valueOf(formattedMessage.split(" ")[1]),
        pickupPoint = configuredJson.decodeFromString(Point.serializer(), formattedMessage.split(" ")[2]),
        destinationPoint = configuredJson.decodeFromString(Point.serializer(), formattedMessage.split(" ")[3])
    )
}
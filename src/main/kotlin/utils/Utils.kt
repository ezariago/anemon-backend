package me.ezar.anemon.utils

import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import me.ezar.anemon.routes.dto.AccountVehiclePreference
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max

lateinit var ktorLogger: Logger

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }

fun String.frameText(): Frame.Text {
    return Frame.Text(this)
}

fun calculateTariff(vehicle: AccountVehiclePreference, distanceMeters: Int): Long {
    return when (vehicle) {
        AccountVehiclePreference.MOTORCYCLE -> 800L * max(4, distanceMeters / 1000)
        AccountVehiclePreference.CAR -> 2000L * max(4, distanceMeters / 1000)
        AccountVehiclePreference.PASSENGER -> throw IllegalArgumentException("Passenger vehicle preference is not supported for routing")
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun String.toBase64(): String {
    return Base64.encode(this.toByteArray())
}

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): String {
    return Base64.decode(this).decodeToString()
}
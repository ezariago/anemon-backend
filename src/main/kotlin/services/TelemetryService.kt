package me.ezar.anemon.services

import me.ezar.anemon.utils.dbQuery
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

enum class TelemetryEventType {
    DRIVER_REGISTER_ROUTE, // Pengemudi menawarkan tumpangan
    PASSENGER_REQUEST_RIDE, // Penumpang mencari tumpangan
    TRIP_CREATED,          // Perjalanan dibuat setelah match
    TRIP_COMPLETED,        // Perjalanan selesai dengan sukses
    TRIP_CANCELLED         // Perjalanan dibatalkan
}

class TelemetryService(database: Database) {
    object TelemetryEvents : Table("telemetry_events") {
        val id = integer("id").autoIncrement()
        val timestamp = datetime("timestamp")
        val eventType = varchar("event_type", 50)
        val driverUid = integer("driver_uid").nullable()
        val passengerUid = integer("passenger_uid").nullable()
        val tripId = varchar("trip_id", 100).nullable()
        val distanceMeters = integer("distance_meters").nullable()
        val tariffRupiah = long("tariff_rupiah").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(TelemetryEvents)
        }
    }

    suspend fun logEvent(
        eventType: TelemetryEventType,
        driverUid: Int? = null,
        passengerUid: Int? = null,
        tripId: String? = null,
        distanceMeters: Int? = null,
        tariffRupiah: Long? = null
    ) {
        dbQuery {
            TelemetryEvents.insert {
                it[this.eventType] = eventType.name
                it[this.timestamp] = LocalDateTime.now()
                it[this.driverUid] = driverUid
                it[this.passengerUid] = passengerUid
                it[this.tripId] = tripId
                it[this.distanceMeters] = distanceMeters
                it[this.tariffRupiah] = tariffRupiah
            }
        }
    }
}
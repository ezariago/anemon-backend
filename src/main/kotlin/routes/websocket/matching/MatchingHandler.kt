package me.ezar.anemon.routes.websocket.matching

import clients.GeocodingClient
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import matching.models.Point
import me.ezar.anemon.clients.RoutingClient
import me.ezar.anemon.routes.dto.AccountVehiclePreference
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.routes.websocket.matching.messages.MatchCancelMessage
import me.ezar.anemon.routes.websocket.matching.messages.MatchFoundMessage
import me.ezar.anemon.routes.websocket.matching.messages.TripRequestMessage
import me.ezar.anemon.routes.websocket.matching.models.LineSegment
import me.ezar.anemon.routes.websocket.trip.TripHandler
import me.ezar.anemon.services.TelemetryEventType
import me.ezar.anemon.services.TelemetryService
import me.ezar.anemon.utils.calculateTariff
import me.ezar.anemon.utils.frameText
import me.ezar.anemon.utils.ktorLogger
import java.util.concurrent.ConcurrentHashMap

class MatchingHandler(
    private val routingClient: RoutingClient,
    private val geocodingClient: GeocodingClient,
    private val telemetryService: TelemetryService,
    private val tripHandler: TripHandler
) {
    private val connectionPool = ConcurrentHashMap<UserProfile, DefaultWebSocketSession>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val driversInTrip = ConcurrentHashMap<UserProfile, String>()

    init {
        tripHandler.onTripEndCallback = { driver ->
            scope.launch {
                mutex.withLock {
                    ktorLogger.info("Trip ended for driver ${driver.name}. Removing from driversInTrip pool.")
                    driversInTrip.remove(driver)
                }
            }
        }
    }

    data class PassengerRequest(
        val vehiclePreference: AccountVehiclePreference,
        val pickup: Point,
        val destination: Point,
        val session: DefaultWebSocketSession,
        val distanceMeters: Int,
        val tariff: Long
    )

    private val waitingPassengers = ConcurrentHashMap<UserProfile, PassengerRequest>()

    data class DriverDetails(
        val route: List<LineSegment>,
        val session: DefaultWebSocketSession,
        val availableSlots: Int
    )

    private val waitingDrivers = ConcurrentHashMap<UserProfile, DriverDetails>()

    private val MATCHING_DISTANCE_THRESHOLD_METERS = 500.0
    private val mutex = Mutex()

    suspend fun registerPassenger(
        userProfile: UserProfile,
        vehiclePreference: AccountVehiclePreference,
        pickup: Point,
        destination: Point,
        session: DefaultWebSocketSession
    ) {
        val routeInfo = routingClient.calculateRoute(
            pickup.latitude, pickup.longitude, null, null,
            destination.latitude, destination.longitude
        )
        val distanceMeters = routeInfo.second.toInt()
        val tariff = calculateTariff(vehiclePreference, distanceMeters)

        telemetryService.logEvent(
            eventType = TelemetryEventType.PASSENGER_REQUEST_RIDE,
            passengerUid = userProfile.uid,
            distanceMeters = distanceMeters,
            tariffRupiah = tariff
        )

        mutex.withLock {
            connectionPool[userProfile] = session
            waitingPassengers[userProfile] =
                PassengerRequest(vehiclePreference, pickup, destination, session, distanceMeters, tariff)
            ktorLogger.info("Passenger registered: ${userProfile.name}. Waiting passenger: ${waitingPassengers.size}")

            val matchedDrivers = findCandidatesForPassenger(pickup, destination, vehiclePreference)

            if (matchedDrivers.isNotEmpty()) {
                ktorLogger.info("Found ${matchedDrivers.size} potential drivers for ${userProfile.name}.")
                matchedDrivers.forEach {
                    val driverSession = waitingDrivers[it]?.session
                    driverSession?.send(
                        TripRequestMessage(
                            passengerProfile = userProfile,
                            pickupAddress = geocodingClient.doReverseGeocodeRequest(pickup.latitude, pickup.longitude),
                            destinationAddress = geocodingClient.doReverseGeocodeRequest(
                                destination.latitude,
                                destination.longitude
                            ),
                            tariff = tariff
                        ).toWebsocketMessageString().frameText()
                    )
                    ktorLogger.info("Sent trip request to driver: ${it.name}")
                }
            } else {
                ktorLogger.info("No suitable drivers found for ${userProfile.name} at this time.")
            }
        }
    }

    suspend fun updateDriverRoutes(
        userProfile: UserProfile,
        route: List<LineSegment>,
        session: DefaultWebSocketSession
    ) {
        mutex.withLock {
            if (waitingDrivers[userProfile] == null) {
                ktorLogger.error("Driver (${userProfile.name}) updated the route but doesn't seem to be waiting for passenger")
                return@withLock
            }
            connectionPool[userProfile] = session
            waitingDrivers[userProfile] = waitingDrivers[userProfile]!!.copy(route = route)
            ktorLogger.info("Driver updated route: ${userProfile.name}")
        }
    }

    suspend fun registerDriver(
        userProfile: UserProfile,
        route: List<LineSegment>,
        availableSlots: Int,
        session: DefaultWebSocketSession
    ) {
        telemetryService.logEvent(
            eventType = TelemetryEventType.DRIVER_REGISTER_ROUTE,
            driverUid = userProfile.uid
        )

        mutex.withLock {
            connectionPool[userProfile] = session
            waitingDrivers[userProfile] = DriverDetails(route, session, availableSlots)
            ktorLogger.info("Driver registered: ${userProfile.name} with $availableSlots slots. Waiting drivers: ${waitingDrivers.size}")

            val matchedPassenger = findMatchForDriver(userProfile, route)

            if (matchedPassenger != null) {
                ktorLogger.info("Found a waiting passenger (${matchedPassenger.name}) for new driver ${userProfile.name}.")
                val passengerRequest = waitingPassengers[matchedPassenger]
                if (passengerRequest != null) {
                    session.send(
                        TripRequestMessage(
                            passengerProfile = matchedPassenger,
                            pickupAddress = geocodingClient.doReverseGeocodeRequest(
                                passengerRequest.pickup.latitude,
                                passengerRequest.pickup.longitude
                            ),
                            destinationAddress = geocodingClient.doReverseGeocodeRequest(
                                passengerRequest.destination.latitude,
                                passengerRequest.destination.longitude
                            ),
                            tariff = passengerRequest.tariff
                        ).toWebsocketMessageString().frameText()
                    )
                    ktorLogger.info("Sent trip request to newly online driver: ${userProfile.name}")
                }
            }
        }
    }

    private fun findMatchForDriver(driverProfile: UserProfile, driverRoute: List<LineSegment>): UserProfile? {
        return waitingPassengers.entries
            .mapNotNull { (passengerProfile, passengerRequest) ->
                if (driverProfile.vehiclePreference != passengerRequest.vehiclePreference) {
                    return@mapNotNull null
                }

                val pickupDistance = driverRoute.minOfOrNull { distanceToSegment(passengerRequest.pickup, it) }
                val destinationDistance =
                    driverRoute.minOfOrNull { distanceToSegment(passengerRequest.destination, it) }

                if (pickupDistance != null && destinationDistance != null &&
                    pickupDistance <= MATCHING_DISTANCE_THRESHOLD_METERS &&
                    destinationDistance <= MATCHING_DISTANCE_THRESHOLD_METERS
                ) {

                    val score = (pickupDistance + destinationDistance) / 2
                    passengerProfile to score
                } else {
                    null
                }
            }
            .sortedBy { it.second }
            .map { it.first }
            .firstOrNull()
    }

    suspend fun handleTripAccept(driverProfile: UserProfile, passengerProfile: UserProfile) {
        mutex.withLock {
            val passengerRequest = waitingPassengers[passengerProfile] ?: return
            val driverDetails = waitingDrivers[driverProfile] ?: return
            val passengerSession = passengerRequest.session
            val driverSession = driverDetails.session

            val tripId: String

            if (driversInTrip.containsKey(driverProfile)) {
                tripId = driversInTrip.getValue(driverProfile)
                ktorLogger.info("Driver ${driverProfile.name} is already in trip $tripId. Adding passenger ${passengerProfile.name}.")
                tripHandler.addPassengerToTrip(tripId, passengerProfile, passengerRequest)
            } else {
                ktorLogger.info("Driver ${driverProfile.name} is starting a new trip with passenger ${passengerProfile.name}.")
                tripId = tripHandler.createTrip(
                    driverProfile,
                    passengerProfile,
                    passengerRequest,
                    driverDetails.availableSlots
                )
                driversInTrip[driverProfile] = tripId
            }

            driverSession.send(Frame.Text(MatchFoundMessage(passengerProfile, tripId).toWebsocketMessageString()))
            passengerSession.send(Frame.Text(MatchFoundMessage(driverProfile, tripId).toWebsocketMessageString()))

            ktorLogger.info("Match successful for Driver ${driverProfile.name} and Passenger ${passengerProfile.name}. Trip ID: $tripId.")

            connectionPool.remove(passengerProfile)
            waitingPassengers.remove(passengerProfile)

            ktorLogger.info("Removed passenger ${passengerProfile.name} from waiting pool. Passengers waiting: ${waitingPassengers.size}")
        }
    }

    private suspend fun findCandidatesForPassenger(
        passengerPickup: Point,
        passengerDestination: Point,
        passengerPreference: AccountVehiclePreference
    ): List<UserProfile> {
        return waitingDrivers.entries
            .mapNotNull { (driverProfile, driverDetails) ->
                // cek si kendaraannya udah bener apa blom
                if (driverProfile.vehiclePreference != passengerPreference) {
                    return@mapNotNull null
                }

                val driverInTripId = driversInTrip[driverProfile]
                if (driverInTripId != null) {
                    val tripState = tripHandler.getTripState(driverInTripId)
                    // If the driver is in a trip and that trip is full, they are not a candidate.
                    if (tripState != null && tripState.passengers.size >= tripState.availableSlots) {
                        return@mapNotNull null
                    }
                }

                // awal awal tu cari jarak antara titik jemput dgn segmen garis paling deketnya, pake proyeksi ortogonal
                val nearestPickupSegment =
                    driverDetails.route.minByOrNull { distanceToSegment(passengerPickup, it) } ?: return@mapNotNull null
                val pickupDistance = distanceToSegment(passengerPickup, nearestPickupSegment)

                // sama aja, tpi buat titik anter
                val nearestDropoffSegment =
                    driverDetails.route.minByOrNull { distanceToSegment(passengerDestination, it) }
                        ?: return@mapNotNull null
                val dropoffDistance = distanceToSegment(passengerDestination, nearestDropoffSegment)

                if (
                    pickupDistance <= MATCHING_DISTANCE_THRESHOLD_METERS &&
                    dropoffDistance <= MATCHING_DISTANCE_THRESHOLD_METERS
                ) {
                    // nah klo jarak kasarnya udah masuk, skrg cari jarak realnya, pake routing client
                    // fungsinya ya kadang ada jalan yang ruwet, kayak satu arah, dimana jarak ortogonal di mapnya itu ga sesuai dgn jarak real
                    // karena harus puter balik gt gt

                    // ambil dri api google
                    val pickupRealDistance = routingClient.calculateRoute(
                        passengerPickup.latitude,
                        passengerPickup.longitude,
                        null,
                        null,
                        nearestPickupSegment.start.latitude,
                        nearestPickupSegment.start.longitude
                    ).second.toInt()

                    // juga dari api google
                    val dropoffRealDistance = routingClient.calculateRoute(
                        passengerDestination.latitude,
                        passengerDestination.longitude,
                        null,
                        null,
                        nearestDropoffSegment.end.latitude,
                        nearestDropoffSegment.end.longitude
                    ).second.toInt()

                    val score = (pickupRealDistance + dropoffRealDistance) / 2
                    driverProfile to score
                } else {
                    null
                }
            }
            .sortedBy { it.second }
            .map {
                it.first
            }
    }

    suspend fun stopMatching(userProfile: UserProfile) {
        mutex.withLock {
            connectionPool.remove(userProfile)
            if (waitingPassengers.remove(userProfile) != null) {
                ktorLogger.info("Passenger ${userProfile.name} gracefully stopped matching.")
                waitingDrivers.values.forEach { driverDetails ->
                    driverDetails.session.send(
                        MatchCancelMessage(userProfile).toWebsocketMessageString().frameText()
                    )
                }
            }
            if (waitingDrivers.remove(userProfile) != null) {
                ktorLogger.info("Driver ${userProfile.name} gracefully stopped matching.")
            }
        }
    }

    suspend fun onClientDisconnect(session: DefaultWebSocketSession) {
        mutex.withLock {
            val userEntry = connectionPool.entries.firstOrNull { it.value == session }
            if (userEntry == null) {
                ktorLogger.info("A disconnected session was not found in the connection pool. No action taken.")
                return@withLock
            }
            val userProfile = userEntry.key
            connectionPool.remove(userProfile)

            ktorLogger.info("Client ${userProfile.name} disconnected unexpectedly, removing from pools.")
            if (waitingPassengers.remove(userProfile) != null) {
                ktorLogger.info("${userProfile.name} removed from waiting passengers.")
                waitingDrivers.values.forEach {
                    it.session.send(
                        MatchCancelMessage(userProfile).toWebsocketMessageString().frameText()
                    )
                }
            }
            if (waitingDrivers.remove(userProfile) != null) {
                ktorLogger.info("${userProfile.name} removed from waiting drivers.")
                driversInTrip.remove(userProfile)
            }
        }
    }
}
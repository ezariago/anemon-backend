package me.ezar.anemon.routes.websocket.trip

import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import matching.models.Point
import me.ezar.anemon.clients.RoutingClient
import me.ezar.anemon.routes.dto.UserProfile
import me.ezar.anemon.routes.dto.UserStatus
import me.ezar.anemon.routes.websocket.matching.MatchingHandler
import me.ezar.anemon.routes.websocket.trip.messages.createCancelRequestBroadcastMessage
import me.ezar.anemon.routes.websocket.trip.messages.createErrorMessage
import me.ezar.anemon.routes.websocket.trip.messages.createLocationBroadcastMessage
import me.ezar.anemon.routes.websocket.trip.messages.createPolylineUpdateMessage
import me.ezar.anemon.routes.websocket.trip.messages.createTripStateUpdateMessage
import me.ezar.anemon.routes.websocket.trip.models.*
import me.ezar.anemon.services.TelemetryEventType
import me.ezar.anemon.services.TelemetryService
import me.ezar.anemon.utils.frameText
import me.ezar.anemon.utils.ktorLogger
import org.json.JSONException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class TripHandler(
    private val routingClient: RoutingClient,
    private val telemetryService: TelemetryService
) {
    private val activeTrips = ConcurrentHashMap<String, Trip>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()
    var onTripEndCallback: ((driver: UserProfile) -> Unit)? = null

    fun createTrip(
        driver: UserProfile,
        passenger: UserProfile,
        passengerRequest: MatchingHandler.PassengerRequest,
        availableSlots: Int
    ): String {
        val tripId = UUID.randomUUID().toString()
        val tripState = TripState(
            tripId = tripId,
            driver = driver,
            passengers = mutableMapOf(
                passenger to TripDetails(
                    pickupPoint = passengerRequest.pickup,
                    destinationPoint = passengerRequest.destination,
                    status = PassengerTripStatus.WAITING_FOR_PICKUP,
                    distanceMeters = passengerRequest.distanceMeters,
                    tariffRupiah = passengerRequest.tariff
                )
            ),
            status = TripStatus.AWAITING_PARTICIPANTS,
            availableSlots = availableSlots
        )
        activeTrips[tripId] = Trip(tripState)
        ktorLogger.info("Trip created with ID: $tripId for driver ${driver.name} with $availableSlots slots and initial passenger ${passenger.name}")

        scope.launch {
            telemetryService.logEvent(
                eventType = TelemetryEventType.TRIP_CREATED,
                driverUid = driver.uid,
                passengerUid = passenger.uid,
                tripId = tripId,
                distanceMeters = passengerRequest.distanceMeters,
                tariffRupiah = passengerRequest.tariff
            )
        }

        return tripId
    }

    suspend fun addPassengerToTrip(
        tripId: String,
        passenger: UserProfile,
        passengerRequest: MatchingHandler.PassengerRequest
    ) = mutex.withLock {
        val trip = activeTrips[tripId] ?: run {
            ktorLogger.info("Cannot add passenger, trip $tripId not found.")
            return
        }

        if (trip.state.passengers.size >= trip.state.availableSlots) {
            ktorLogger.info("Cannot add passenger, trip $tripId is full. (Slots: ${trip.state.availableSlots})")
            return
        }

        trip.state.passengers[passenger] = TripDetails(
            pickupPoint = passengerRequest.pickup,
            destinationPoint = passengerRequest.destination,
            status = PassengerTripStatus.WAITING_FOR_PICKUP,
            distanceMeters = passengerRequest.distanceMeters,
            tariffRupiah = passengerRequest.tariff
        )

        ktorLogger.info("Added passenger ${passenger.name} to existing trip $tripId. Total passengers: ${trip.state.passengers.size}")

        broadcastState(trip)
    }


    suspend fun joinTrip(tripId: String, user: UserProfile, session: DefaultWebSocketSession) = mutex.withLock {
        val trip = activeTrips[tripId]
        if (trip == null) {
            ktorLogger.error("Trip $tripId not found for user ${user.name}.")
            session.send(createErrorMessage("Trip not found").frameText())
            session.close(CloseReason(CloseReason.Codes.NORMAL, "Trip not found"))
            return
        }

        ktorLogger.info("User ${user.name} reconnected, cancelling cleanup job for trip $tripId.")

        trip.connections[user] = session
        ktorLogger.info("User ${user.name} joined trip ${trip.state.tripId}")

        val allParticipants = trip.state.passengers.keys + trip.state.driver

        if (trip.state.status == TripStatus.AWAITING_PARTICIPANTS && trip.connections.keys.containsAll(allParticipants)) {
            trip.state.status = TripStatus.EN_ROUTE_TO_PICKUP
            ktorLogger.info("All participants joined trip ${trip.state.tripId}. Status is now EN_ROUTE_TO_PICKUP.")
        }

        if (trip.state.status == TripStatus.RECONNECTING && trip.connections.keys.containsAll(allParticipants)) {
            trip.state.status =
                if (trip.state.passengers.values.all { it.status == PassengerTripStatus.WAITING_FOR_PICKUP }) {
                    TripStatus.EN_ROUTE_TO_PICKUP
                } else {
                    TripStatus.IN_PROGRESS
                }
            ktorLogger.info("All participants reconnected to trip ${trip.state.tripId}. Status restored to ${trip.state.status}.")
        }

        broadcastState(trip)
    }

    suspend fun updateLocation(tripId: String, sender: UserProfile, location: Point) {
        val trip = activeTrips[tripId] ?: return
        val message = createLocationBroadcastMessage(sender, location).frameText()

        scope.launch {
            broadcastPolylineUpdate(trip, location)
        }

        trip.connections.filter { it.key != sender }.values.forEach { session ->
            try {
                session.send(message.copy())
            } catch (e: Exception) {
                ktorLogger.info("Failed to relay location for trip $tripId: ${e.message}")
            }
        }
    }

    suspend fun passengerPickedUp(tripId: String, driver: UserProfile, passenger: UserProfile) = mutex.withLock {
        val trip = activeTrips[tripId] ?: return
        if (trip.state.driver != driver) return

        val passengerDetails = trip.state.passengers[passenger] ?: return
        passengerDetails.status = PassengerTripStatus.IN_TRANSIT

        trip.state.status = TripStatus.IN_PROGRESS
        ktorLogger.info("Passenger ${passenger.name} picked up for trip $tripId. Status is now IN_PROGRESS.")
        broadcastState(trip)
    }

    suspend fun passengerDroppedOff(tripId: String, driver: UserProfile, passenger: UserProfile) = mutex.withLock {
        val trip = activeTrips[tripId] ?: return
        if (trip.state.driver != driver) return

        val passengerDetails = trip.state.passengers[passenger] ?: return
        passengerDetails.status = PassengerTripStatus.DROPPED_OFF
        ktorLogger.info("Passenger ${passenger.name} dropped off for trip $tripId.")

        if (trip.state.passengers.values.all { it.status == PassengerTripStatus.DROPPED_OFF }) {
            trip.state.status = TripStatus.COMPLETED
            ktorLogger.info("All passengers dropped off. Trip $tripId is COMPLETED.")
            broadcastState(trip)
            cleanupTrip(tripId)
        } else {
            broadcastState(trip)
        }
    }

    suspend fun onClientDisconnect(tripId: String, user: UserProfile) = mutex.withLock {
        val trip = activeTrips[tripId] ?: return

        trip.connections.remove(user)
        ktorLogger.info("User ${user.name} disconnected from trip $tripId.")

        if (trip.state.status != TripStatus.COMPLETED && trip.state.status != TripStatus.CANCELLED) {
            trip.state.status = TripStatus.RECONNECTING
            broadcastState(trip)
            ktorLogger.info("Trip $tripId is now in RECONNECTING state.")
        }
    }

    fun findTripForUser(userProfile: UserProfile): Pair<String, UserStatus>? {
        for ((tripId, trip) in activeTrips) {
            if (trip.state.driver == userProfile) {
                return Pair(tripId, UserStatus.IN_TRIP_AS_DRIVER)
            }
            if (trip.state.passengers.containsKey(userProfile)) {
                return Pair(tripId, UserStatus.IN_TRIP_AS_PASSENGER)
            }
        }
        return null
    }

    fun getTripState(tripId: String): TripState? {
        return activeTrips[tripId]?.state
    }

    suspend fun requestCancellation(tripId: String, requester: UserProfile) = mutex.withLock {
        val trip = activeTrips[tripId] ?: return

        if (!trip.state.cancellationRequesters.add(requester)) {
            ktorLogger.info("User ${requester.name} sent a duplicate cancellation request for trip $tripId.")
            return
        }
        ktorLogger.info("User ${requester.name} requested to cancel trip $tripId.")

        val activeParticipants = trip.state.passengers
            .filter { it.value.status != PassengerTripStatus.DROPPED_OFF }
            .keys + trip.state.driver

        if (trip.state.cancellationRequesters.containsAll(activeParticipants)) {
            ktorLogger.info("Consensus reached for cancelling trip $tripId.")
            trip.state.status = TripStatus.CANCELLED
            broadcastState(trip)
            cleanupTrip(tripId)
        } else {
            val message = createCancelRequestBroadcastMessage(requester).frameText()
            trip.broadcast(message)
            ktorLogger.info("Broadcasted cancellation request from ${requester.name} for trip $tripId.")
            broadcastState(trip)
        }
    }

    private suspend fun broadcastPolylineUpdate(trip: Trip, driverLoc: Point) {
        val passengerTripDetails = trip.state.passengers.values.first()
        val target = when (passengerTripDetails.status) {
            PassengerTripStatus.WAITING_FOR_PICKUP ->
                passengerTripDetails.pickupPoint

            PassengerTripStatus.IN_TRANSIT ->
                passengerTripDetails.destinationPoint
            else -> {
                return
            }
        }
        try {
            trip.broadcast(createPolylineUpdateMessage(routingClient.calculateRoute(
                driverLoc.latitude,
                driverLoc.longitude,
                null,
                null,
                target.latitude,
                target.longitude,
            ).first).frameText())
        } catch (ex: JSONException) {
            ktorLogger.info("Distance route not found (${driverLoc.latitude}, ${driverLoc.longitude}) to (${target.latitude}, ${target.longitude}")
        }
    }

    private suspend fun broadcastState(trip: Trip) {
        val message = createTripStateUpdateMessage(trip.state).frameText()
        trip.broadcast(message)
    }

    private suspend fun cleanupTrip(tripId: String) {
        val trip = activeTrips.remove(tripId) ?: return

        when (trip.state.status) {
            TripStatus.COMPLETED -> {
                trip.state.passengers.forEach { (passenger, details) ->
                    telemetryService.logEvent(
                        eventType = TelemetryEventType.TRIP_COMPLETED,
                        driverUid = trip.state.driver.uid,
                        passengerUid = passenger.uid,
                        tripId = tripId,
                        distanceMeters = details.distanceMeters,
                        tariffRupiah = details.tariffRupiah
                    )
                }
            }

            TripStatus.CANCELLED -> {
                telemetryService.logEvent(
                    eventType = TelemetryEventType.TRIP_CANCELLED,
                    driverUid = trip.state.driver.uid,
                    tripId = tripId
                )
            }

            else -> {}
        }

        trip.state.driver.let { onTripEndCallback?.invoke(it) }
        trip.connections.values.forEach {
            it.close(CloseReason(CloseReason.Codes.NORMAL, "Trip ended"))
        }
        ktorLogger.info("Cleaned up and closed connections for trip $tripId.")
    }
}
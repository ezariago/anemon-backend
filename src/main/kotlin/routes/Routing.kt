package me.ezar.anemon.routes

import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import me.ezar.anemon.clients.RoutingClient
import me.ezar.anemon.routes.dto.RoutingPreviewRequest
import me.ezar.anemon.routes.dto.RoutingPreviewResponse
import me.ezar.anemon.routes.dto.UserProfilePrincipal
import me.ezar.anemon.routes.websocket.matching.MatchingHandler
import me.ezar.anemon.routes.websocket.matching.enum.MatchingAction
import me.ezar.anemon.routes.websocket.matching.messages.RegisterDriverMessage
import me.ezar.anemon.routes.websocket.matching.messages.RegisterPassengerMessage
import me.ezar.anemon.routes.websocket.matching.messages.TripAcceptMessage
import me.ezar.anemon.routes.websocket.matching.messages.UpdateDriverRouteMessage
import me.ezar.anemon.routes.websocket.trip.TripHandler
import me.ezar.anemon.routes.websocket.trip.enum.TripAction
import me.ezar.anemon.routes.websocket.trip.messages.JoinTripMessage
import me.ezar.anemon.routes.websocket.trip.messages.PassengerActionMessage
import me.ezar.anemon.routes.websocket.trip.messages.UpdateLocationMessage
import me.ezar.anemon.utils.calculateTariff
import me.ezar.anemon.utils.ktorLogger

fun Route.mapsRouting(
    routingClient: RoutingClient,
    matchingHandler: MatchingHandler,
    tripHandler: TripHandler
) {
    route("/routing") {
        post("/preview") {
            val request = call.receive<RoutingPreviewRequest>()
            val clientResponse = routingClient.calculateRoute(
                request.originLat,
                request.originLng,
                null,
                null,
                request.destinationLat,
                request.destinationLng,
            )
            val encodedPolyline = clientResponse.first
            val distanceMeters = clientResponse.second.toInt()
            val tariffRupiah = calculateTariff(
                vehicle = request.vehiclePreference,
                distanceMeters = distanceMeters
            )
            call.respond(
                RoutingPreviewResponse(
                    encodedPolyline,
                    distanceMeters,
                    tariffRupiah
                )
            )
        }

        authenticate {
            webSocket("/matching") {
                val principal = call.principal<UserProfilePrincipal>() ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not authenticated"))
                    return@webSocket
                }
                val userProfile = principal.userProfile
                ktorLogger.info("[Websocket Matching] Client connected: ${userProfile.name}")

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val rawMessage = frame.readText()
                            val actionString = rawMessage.split(" ").getOrNull(0)

                            if (actionString == null) {
                                ktorLogger.info("Received invalid message: $rawMessage")
                                continue
                            }

                            when (val action = MatchingAction.valueOf(actionString)) {
                                MatchingAction.REGISTER_PASSENGER -> {
                                    val msg = RegisterPassengerMessage(rawMessage)
                                    matchingHandler.registerPassenger(
                                        userProfile,
                                        msg.vehicle,
                                        msg.pickupPoint,
                                        msg.destinationPoint,
                                        this
                                    )
                                }

                                MatchingAction.REGISTER_DRIVER -> {
                                    val msg = RegisterDriverMessage(rawMessage)
                                    matchingHandler.registerDriver(
                                        userProfile,
                                        msg.route,
                                        msg.availableSlots,
                                        this
                                    )
                                }

                                MatchingAction.TRIP_ACCEPT -> {
                                    val msg = TripAcceptMessage(rawMessage)
                                    matchingHandler.handleTripAccept(
                                        driverProfile = userProfile,
                                        passengerProfile = msg.passengerProfile
                                    )
                                }

                                MatchingAction.STOP_MATCHING -> {
                                    matchingHandler.stopMatching(userProfile)
                                }

                                MatchingAction.UPDATE_DRIVER_ROUTE -> {
                                    val msg = UpdateDriverRouteMessage(rawMessage)
                                    matchingHandler.updateDriverRoutes(userProfile, msg.route, this)
                                }

                                else -> {
                                    ktorLogger.info("Received unsupported action '$action' from client ${userProfile.name}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    ktorLogger.info("Error in WebSocket session for ${userProfile.name}: ${e.message}")
                    e.printStackTrace()
                } finally {
                    matchingHandler.onClientDisconnect(this)
                    ktorLogger.info("[Websocket Matching] Client disconnected: ${userProfile.name}")
                }
            }
            webSocket("/trip") {
                val principal = call.principal<UserProfilePrincipal>() ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not authenticated"))
                    return@webSocket
                }
                val userProfile = principal.userProfile
                var currentTripId: String? = null

                ktorLogger.info("[Websocket Trip] Client connected: ${userProfile.name}")

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val rawMessage = frame.readText()
                            val actionString = rawMessage.split(" ").getOrNull(0) ?: continue
                            val action = try {
                                TripAction.valueOf(actionString)
                            } catch (_: IllegalArgumentException) {
                                ktorLogger.info("Received unknown trip action: $actionString")
                                continue
                            }

                            when (action) {
                                TripAction.JOIN_TRIP -> {
                                    val msg = JoinTripMessage.fromRawMessage(rawMessage)
                                    currentTripId = msg.tripId
                                    tripHandler.joinTrip(msg.tripId, userProfile, this)
                                }

                                TripAction.UPDATE_LOCATION -> {
                                    currentTripId?.let { tripId ->
                                        val msg = UpdateLocationMessage(rawMessage)
                                        tripHandler.updateLocation(tripId, userProfile, msg.location)
                                    }
                                }

                                TripAction.PICKUP_PASSENGER -> {
                                    currentTripId?.let { tripId ->
                                        val msg = PassengerActionMessage(rawMessage)
                                        tripHandler.passengerPickedUp(tripId, userProfile, msg.passenger)
                                    }
                                }

                                TripAction.DROPOFF_PASSENGER -> {
                                    currentTripId?.let { tripId ->
                                        val msg = PassengerActionMessage(rawMessage)
                                        tripHandler.passengerDroppedOff(tripId, userProfile, msg.passenger)
                                    }
                                }

                                TripAction.UPDATE_CANCELLATION -> {
                                    currentTripId?.let { tripId ->
                                        tripHandler.requestCancellation(tripId, userProfile)
                                    }
                                }

                                else -> {
                                    ktorLogger.info("Client ${userProfile.name} sent a server-only action: $action")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    ktorLogger.info("Error in trip websocket session for ${userProfile.name}: ${e.message}")
                } finally {
                    currentTripId?.let {
                        tripHandler.onClientDisconnect(it, userProfile)
                    }
                    ktorLogger.info("[Websocket Trip] Client disconnected: ${userProfile.name}")
                }
            }
        }
    }
}
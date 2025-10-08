package me.ezar.anemon.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.ezar.anemon.MINIMUM_APP_VERSION
import me.ezar.anemon.routes.dto.*
import me.ezar.anemon.routes.websocket.trip.TripHandler
import me.ezar.anemon.services.UserService
import me.ezar.anemon.session.generateToken
import me.ezar.anemon.utils.FileManager.saveAsImageFile
import me.ezar.anemon.utils.ktorLogger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun Route.users(
    userService: UserService,
    tripHandler: TripHandler
) {
    route("/users") {
        post {
            val userRequest = call.receive<UserCreateRequest>()
            val vehicleFilename = userRequest.vehicleImageEncoded?.let {
                Base64.decode(it)
            }?.saveAsImageFile()
            val profilePictureFile = Base64.decode(userRequest.profilePictureEncoded)
                .saveAsImageFile()


            val userId = try {
                userService.create(userRequest, vehicleFilename?.name, profilePictureFile.name)
            } catch (ex: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Failed to create user: ${ex.message}")
                )
                return@post
            }
            call.respond(
                HttpStatusCode.Created,
                mapOf("message" to "User created successfully", "uid" to userId.toString())
            )
        }

        post("/login") {
            val credentials = call.receive<LoginRequest>()
            val userProfile = userService.login(credentials) ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("message" to "Invalid email or password")
                )
                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                LoginResponse(token = userProfile.generateToken(), profile = userProfile.toUserProfile())
            )
        }

        authenticate {
            get("/profile") {
                val principal = call.principal<UserProfilePrincipal>()!!
                val appVersionCode = call.request.headers["appVersion"]?.toIntOrNull()

                if (appVersionCode == null || appVersionCode < MINIMUM_APP_VERSION) {
                    call.respond(
                        HttpStatusCode.UpgradeRequired,
                        "Ada update baru nih, download dulu yaa di playstore :D"
                    )
                    return@get
                }

                call.respond(principal.userProfile)
                ktorLogger.info("User profile accessed: ${principal.userProfile.email}")
            }

            get("/state") {
                val principal = call.principal<UserProfilePrincipal>() ?: return@get
                val userProfile = principal.userProfile

                val tripInfo = tripHandler.findTripForUser(userProfile)
                if (tripInfo != null) {
                    val (tripId, status) = tripInfo
                    call.respond(UserStateResponse(status = status, tripId = tripId))
                    return@get
                }
                return@get call.respond(
                    UserStateResponse(status = UserStatus.IDLE, tripId = null)
                )
            }
        }
    }
}
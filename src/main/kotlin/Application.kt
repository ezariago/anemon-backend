package me.ezar.anemon

import clients.GeocodingClient
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import me.ezar.anemon.clients.RoutingClient
import me.ezar.anemon.provider.SecretsProvider
import me.ezar.anemon.routes.geocoding
import me.ezar.anemon.routes.mapsRouting
import me.ezar.anemon.routes.users
import me.ezar.anemon.routes.websocket.matching.MatchingHandler
import me.ezar.anemon.routes.websocket.trip.TripHandler
import me.ezar.anemon.services.TelemetryService
import me.ezar.anemon.services.UserService
import me.ezar.anemon.session.configureJWT
import me.ezar.anemon.utils.ktorLogger
import org.bspfsystems.yamlconfiguration.file.YamlConfiguration
import org.jetbrains.exposed.sql.Database
import java.io.File
import kotlin.time.Duration.Companion.seconds

const val MINIMUM_APP_VERSION = 7

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

val configuredJson = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    allowStructuredMapKeys = true
}

fun Application.module() {
    ktorLogger = log
    install(ContentNegotiation) {
        json(configuredJson)
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val configFile = File("config.yml")
    if (!configFile.exists()) {
        configFile.createNewFile()
        object {}.javaClass.getResourceAsStream("/config.yml").use {
            it!!.copyTo(configFile.outputStream())
        }
    }

    val config = YamlConfiguration.loadConfiguration(configFile)
    SecretsProvider.googleApisKey = config.getString("google-apis").toString()

    val database = Database.connect("jdbc:sqlite:anemon.db", "org.sqlite.JDBC")
    val userService = UserService(database)
    val telemetryService = TelemetryService(database)
    val geocodingClient = GeocodingClient()
    val routingClient = RoutingClient()

    val tripHandler = TripHandler(routingClient, telemetryService)
    val matchingHandler = MatchingHandler(routingClient, geocodingClient, telemetryService, tripHandler)

    configureJWT(userService)
    configureMonitoring()
    setupRouting(userService, geocodingClient, routingClient, matchingHandler, tripHandler)
}

fun Application.setupRouting(
    userService: UserService,
    geocodingClient: GeocodingClient,
    routingClient: RoutingClient,
    matchingHandler: MatchingHandler,
    tripHandler: TripHandler
) {
    routing {
        users(userService, tripHandler)
        geocoding(geocodingClient)
        mapsRouting(
            routingClient,
            matchingHandler,
            tripHandler
        )
        staticFiles("/images", File("images"))
    }
}
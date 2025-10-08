package me.ezar.anemon.clients

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import me.ezar.anemon.provider.SecretsProvider
import org.json.JSONObject

class RoutingClient {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json()
        }
        install(DefaultRequest)

        defaultRequest {
            url("https://routes.googleapis.com/")
        }
    }

    suspend fun calculateRoute(
        originLat: Double,
        originLng: Double,
        intermediateLat: Double?,
        intermediateLng: Double?,
        destinationLat: Double,
        destinationLng: Double
    ): Pair<String, String> {
        val response = client.post("directions/v2:computeRoutes") {
            header("X-Goog-Api-Key", SecretsProvider.googleApisKey)
            header("X-Goog-FieldMask", "routes.polyline,routes.distanceMeters")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "origin": {
                    "location": {
                      "latLng": {
                        "latitude": $originLat,
                        "longitude": $originLng
                      }
                    }
                  },
                  "destination": {
                    "location": {
                      "latLng": {
                        "latitude": $destinationLat,
                        "longitude": $destinationLng
                      }
                    }
                  },
                ${
                    if (intermediateLat != null && intermediateLng != null) {
                        """
                    "intermediates": [
                        {
                            "location": {
                                "latLng": {
                                    "latitude": $intermediateLat,
                                    "longitude": $intermediateLng
                                }
                            }
                        }
                        """.trimIndent()
                    } else {
                        ""
                    }
                }
                  "travelMode": "DRIVE",
                  "polylineQuality": "HIGH_QUALITY"
                }
            """.trimIndent()
            )
        }

        val json = JSONObject(response.bodyAsText())
        val results = json.getJSONArray("routes")
        val polyline = results.getJSONObject(0).getJSONObject("polyline").getString("encodedPolyline")
        val distanceMeters = results.getJSONObject(0).getInt("distanceMeters")
        return Pair(polyline, distanceMeters.toString())
    }
}
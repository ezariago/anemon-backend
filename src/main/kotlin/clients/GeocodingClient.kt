package clients

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

class GeocodingClient {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json()
        }
        install(DefaultRequest)

        defaultRequest {
            url("https://maps.googleapis.com/maps/api/geocode/")
        }
    }

    suspend fun doReverseGeocodeRequest(
        lat: Double,
        lng: Double,
    ): String {
        val response = client.post("json?latlng=$lat,$lng&key=${SecretsProvider.googleApisKey}&language=id") {
            contentType(ContentType.Application.Json)
        }

        val json = JSONObject(response.bodyAsText())
        val results = json.getJSONArray("results")
        return results.getJSONObject(0).getString("formatted_address")
    }
}
package name.hersen.onpace

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.ClientRequestException
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readText
import io.ktor.features.ContentNegotiation
import io.ktor.html.respondHtml
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.jackson.jackson
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import java.net.URLEncoder
import java.time.LocalDate

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(@Suppress("UNUSED_PARAMETER") testing: Boolean = false) {
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    val client = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = JacksonSerializer()
        }
    }

    val redirectUri = URLEncoder.encode(
        "https://onpace-ktor.herokuapp.com/logged-in", "UTF-8"
    )

    routing {
        get("/") {
            call.respondHtml {
                body {
                    a(href = "login") { +"login" }
                }
            }
        }

        get("/login") {
            call.respondRedirect(
                "https://www.strava.com/oauth/authorize?${arrayOf(
                    "client_id=45920",
                    "response_type=code",
                    "scope=read",
                    "redirect_uri=$redirectUri"
                ).joinToString("&")}"
            )
        }

        get("/logged-in") {
            try {
                val code: String = call.request.queryParameters["code"].toString()
                val clientId = System.getenv("CLIENT_ID")
                val clientSecret = System.getenv("CLIENT_SECRET")

                when {
                    clientId == null -> {
                        call.response.status(HttpStatusCode.InternalServerError)
                        call.respondHtml(HttpStatusCode.BadRequest) { body { div { +"CLIENT_ID missing" } } }
                    }
                    clientSecret == null -> {
                        call.response.status(HttpStatusCode.InternalServerError)
                        call.respondHtml(HttpStatusCode.BadRequest) { body { div { +"CLIENT_SECRET missing" } } }
                    }
                    else -> {
                        val authentication: Authentication = client.submitForm(
                            "https://www.strava.com/api/v3/oauth/token",
                            Parameters.build {
                                append("code", code)
                                append("client_id", clientId)
                                append("client_secret", clientSecret)
                                append("grant_type", "authorization_code")
                            }
                        )

                        val activityStats: ActivityStats =
                            client.get("https://www.strava.com/api/v3/athletes/${authentication.athlete.id}/stats") {
                                header("Authorization", "Bearer ${authentication.access_token}")
                            }

                        val distance: Double = activityStats.ytd_run_totals.distance
                        val target: Double = 15e5 * LocalDate.now().dayOfYear / 366.0

                        call.respondHtml {
                            body {
                                div { +"Du har sprungit ${String.format("%.1f", distance * 1e-3)} km i år." }
                                div { +"Målet är ${String.format("%.1f", target * 1e-3)} km." }
                                div { +"Du ligger ${String.format("%.0f", target - distance)} meter efter." }
                            }
                        }
                    }
                }
            } catch (e: ClientRequestException) {
                val httpResponse = e.response
                call.respondText(httpResponse.readText(), contentType = ContentType.Text.Plain)
            }
        }
    }
}

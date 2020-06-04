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
import io.ktor.client.statement.readText
import io.ktor.features.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import java.net.URLEncoder

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
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
            call.respondText("<a href='login'>login</a>", contentType = ContentType.Text.Html)
        }

        get("/login") {
            call.respondRedirect(
                "https://www.strava.com/oauth/authorize?client_id=45920&response_type=code&scope=read&redirect_uri=$redirectUri"
            )
        }

        get("/logged-in") {
            try {
                val code: String = call.request.queryParameters["code"].toString()
                val clientId = System.getenv("CLIENT_ID")
                val clientSecret = System.getenv("CLIENT_SECRET")

                if (clientId == null) {
                    call.response.status(HttpStatusCode.InternalServerError)
                    call.respondText("CLIENT_ID missing")
                } else if (clientSecret == null) {
                    call.response.status(HttpStatusCode.InternalServerError)
                    call.respondText("CLIENT_SECRET missing")
                } else {
                    val authentication = client.submitForm<Authentication>(
                        "https://www.strava.com/api/v3/oauth/token",
                        Parameters.build {
                            append("code", code)
                            append("client_id", clientId)
                            append("client_secret", clientSecret)
                            append("grant_type", "authorization_code")
                        }
                    )
                    println(authentication)
                    call.respondText("Hej ${authentication.athlete.firstname}")
                }
            } catch (e: ClientRequestException) {
                val httpResponse = e.response
                call.respondText(httpResponse.readText(), contentType = ContentType.Text.Plain)
            }
        }

        get("/json/jackson") {
            call.respond(mapOf("hello" to "world"))
        }
    }
}


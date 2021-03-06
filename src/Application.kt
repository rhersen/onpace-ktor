package name.hersen.onpace

import com.fasterxml.jackson.databind.SerializationFeature
import freemarker.cache.ClassTemplateLoader
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
import io.ktor.freemarker.FreeMarker
import io.ktor.freemarker.FreeMarkerContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.jackson.jackson
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.get
import io.ktor.routing.routing
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(@Suppress("UNUSED_PARAMETER") testing: Boolean = false) {
  install(ContentNegotiation) {
    jackson {
      enable(SerializationFeature.INDENT_OUTPUT)
    }
  }

  install(FreeMarker) {
    templateLoader =
      ClassTemplateLoader(this::class.java.classLoader, "templates")
  }

  val client = HttpClient(Apache) {
    install(JsonFeature) {
      serializer = JacksonSerializer()
    }
  }

  val authentications = HashMap<String, Authentication>()

  val redirectUri =
    URLEncoder.encode("https://secure.hersen.name/onpace/authenticated", "UTF-8")
  val localhostUri =
    URLEncoder.encode("http://localhost:1338/onpace/authenticated", "UTF-8")

  routing {
    get("/onpace/") {
      call.respond(FreeMarkerContent("index.ftl", mapOf<String, String>()))
    }

    get("/onpace/login") {
      call.respondRedirect(
        "https://www.strava.com/oauth/authorize?${arrayOf(
          "client_id=45920",
          "response_type=code",
          "scope=read",
          "redirect_uri=${if (call.request.host() == "localhost") localhostUri else redirectUri}"
        ).joinToString("&")}"
      )
    }

    get("/onpace/authenticated") {
      try {
        val code = call.request.queryParameters["code"].toString()
        val clientId = System.getenv("CLIENT_ID")
        val clientSecret = System.getenv("CLIENT_SECRET")

        when {
          clientId == null -> {
            call.response.status(HttpStatusCode.InternalServerError)
            call.respond(
              FreeMarkerContent(
                "missing.ftl", mapOf("field" to "CLIENT_ID")
              )
            )
          }
          clientSecret == null -> {
            call.response.status(HttpStatusCode.InternalServerError)
            call.respond(
              FreeMarkerContent(
                "missing.ftl", mapOf("field" to "CLIENT_SECRET")
              )
            )
          }
          else -> {
            val authentication: Authentication = client.submitForm(
              "https://www.strava.com/api/v3/oauth/token",
              Parameters.build {
                append("code", code)
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("grant_type", "authorization_code")
              })

            val athleteId = authentication.athlete.id.toString()
            authentications[athleteId] = authentication

            call.respond(
              FreeMarkerContent(
                "authenticated.ftl", mapOf(
                  "athleteId" to athleteId,
                  "expiresAt" to Instant.ofEpochSecond(authentication.expires_at.toLong())
                )
              )
            )
          }
        }
      } catch (e: ClientRequestException) {
        val httpResponse = e.response
        call.respondText(
          httpResponse.readText(), contentType = ContentType.Text.Plain
        )
      }
    }

    get("/onpace/athlete/{athleteId}") {
      try {
        val athleteId = call.parameters["athleteId"]

        if (athleteId == null) {
          call.respond(
            FreeMarkerContent(
              "missing.ftl", mapOf("field" to "athleteId")
            )
          )
        } else {
          val authentication = authentications[athleteId]

          if (authentication == null) {
            call.respond(
              FreeMarkerContent(
                "missing.ftl", mapOf("field" to "authentication")
              )
            )
          } else {
            val activityStats: ActivityStats =
              client.get("https://www.strava.com/api/v3/athletes/$athleteId/stats") {
                header(
                  "Authorization",
                  "${authentication.token_type} ${authentication.access_token}"
                )
              }

            val distance = activityStats.ytd_run_totals.distance
            val target = 15e5 * LocalDate.now().dayOfYear / 366.0

            call.respond(
              FreeMarkerContent(
                "athlete.ftl", mapOf(
                  "distance" to String.format("%.1f", distance * 1e-3),
                  "target" to String.format("%.1f", target * 1e-3),
                  "onpaceText" to onpaceText(target, distance),
                  "athleteId" to athleteId,
                  "expiresAt" to Instant.ofEpochSecond(authentication.expires_at.toLong())
                )
              )
            )
          }
        }
      } catch (e: ClientRequestException) {
        val httpResponse = e.response
        call.respondText(
          httpResponse.readText(), contentType = ContentType.Text.Plain
        )
      }
    }
  }
}

private fun onpaceText(target: Double, distance: Double) =
  if (target < distance) "${fmt(distance - target)} meter före"
  else "${fmt(target - distance)} meter efter"

private fun fmt(meters: Double) = String.format("%.0f", meters)

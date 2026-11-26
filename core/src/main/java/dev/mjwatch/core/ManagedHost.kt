package dev.mjwatch.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Host administration uses the same authenticated HTTPS connection as account settings. */
class ManagedHost(private val api: ManagedApi) {
    suspend fun read(route: String) = api.authorized("admin/host/$route")

    suspend fun write(route: String, body: JSONObject = JSONObject()) =
        api.authorized("admin/host/$route", "POST", body)

    suspend fun operation(kind: String, operationId: String, request: JSONObject? = null): JSONObject {
        val started = request?.let { write("$kind/start", it) }
        return withTimeout(360_000) {
            var status = started
            while (true) {
                when (status?.optString("state")) {
                    "complete" -> return@withTimeout status.optJSONObject("result") ?: status
                    "failed" -> throw MaintenanceFailure(status.optString("code"))
                }
                delay(1_500)
                status = try {
                    read("$kind/status?operationId=$operationId")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: java.io.IOException) {
                    if (failure is HttpFailure && failure.status !in setOf(429, 502, 503, 504)) throw failure
                    null // Restart and update briefly interrupt HTTPS.
                }
            }
            error("Недоступное состояние операции")
        }
    }

    suspend fun searchOperation(service: String, action: String, request: JSONObject = JSONObject()): JSONObject {
        val started = write("$service/$action", request)
        if (started.optString("state") in setOf("failed", "busy")) {
            throw MaintenanceFailure(started.optString("code", started.optString("state")))
        }
        return withTimeout(500_000) {
            var status = read("$service/status")
            while (status.optJSONObject("operation")?.optString("state") == "running") {
                delay(2_000)
                status = read("$service/status")
            }
            status
        }
    }
}

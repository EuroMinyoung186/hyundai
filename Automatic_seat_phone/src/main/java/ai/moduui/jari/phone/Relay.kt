package ai.moduui.jari.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 중계 서버 응답. code = HTTP 상태 (0 = 서버에 닿지 않음) */
data class Reply(val code: Int, val body: JSONObject?)

/** 차와 잇는 중계 서버 (시연: PC 의 phone-relay.py, 실서비스: 팀 서버) */
class Relay(private val baseUrl: String) {

    suspend fun pair(code: String): Reply = post("/phone/pair", JSONObject().put("code", code))

    suspend fun send(code: String, message: JSONObject): Reply = post("/phone/send", message.put("code", code))

    suspend fun status(code: String): Reply = call("GET", "/phone/status?code=$code", null)

    private suspend fun post(path: String, body: JSONObject) = call("POST", path, body.toString())

    private suspend fun call(method: String, path: String, body: String?): Reply = withContext(Dispatchers.IO) {
        try {
            val c = URL(baseUrl + path).openConnection() as HttpURLConnection
            c.connectTimeout = 2_000
            c.readTimeout = 2_000
            c.requestMethod = method
            try {
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty()
                Reply(code, runCatching { JSONObject(text) }.getOrNull())
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            Reply(0, null)
        }
    }
}

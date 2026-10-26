package support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 실행 설정 — test.sh 가 앱을 BASE_URL 에 띄운다. */
object Env {
    private fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    val baseUrl = env("BASE_URL", "http://127.0.0.1:8099").trimEnd('/')
    val dbUrl = env("DB_URL", "jdbc:postgresql://db:5432/ccle_seed")
    val dbUser = env("DB_USER", "ccle")
    val dbPassword = env("DB_PASSWORD", "ccle")
    val pgUrl = env("PG_URL", "http://127.0.0.1:15480").trimEnd('/')
}

val mapper = jacksonObjectMapper()

class Resp(val method: String, val path: String, val status: Int, val body: String) {
    val json: JsonNode by lazy { mapper.readTree(body.ifBlank { "null" }) }
    override fun toString() = "$method $path → $status ${body.take(500)}"
}

/** HTTP 호출. 사용자는 X-User-Id 헤더, 멱등 요청은 Idempotency-Key 헤더. */
object Api {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .version(HttpClient.Version.HTTP_1_1).build()

    fun request(method: String, path: String, body: Any? = null, userId: Any? = null, idempotencyKey: String? = null,
                base: String = Env.baseUrl, timeoutMs: Long = 120_000): Resp {
        val payload = body?.let { mapper.writeValueAsString(it) }
        val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
        if (payload != null) b.header("Content-Type", "application/json")
        if (userId != null) b.header("X-User-Id", userId.toString())
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey)
        b.method(method, payload?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(method, path, r.statusCode(), r.body() ?: "")
    }

    fun get(path: String, userId: Any? = null) = request("GET", path, userId = userId)
    fun post(path: String, body: Any?, userId: Any? = null, idempotencyKey: String? = null) =
        request("POST", path, body, userId, idempotencyKey)
    fun put(path: String, body: Any?) = request("PUT", path, body)
    fun delete(path: String, userId: Any? = null) = request("DELETE", path, userId = userId)
}

/** 앱 시계(GET·PUT /admin/clock — 앞으로만 움직인다). */
object Clock {
    private val KST = ZoneId.of("Asia/Seoul")
    fun iso(t: Instant): String = OffsetDateTime.ofInstant(t, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    fun now(): Instant = OffsetDateTime.parse(Api.get("/admin/clock").json["now"].asText()).toInstant()
    fun moveTo(t: Instant): Resp = Api.put("/admin/clock", mapOf("now" to iso(t)))
    fun advance(d: Duration): Instant = now().plus(d).also { moveTo(it) }
}

/** 배치 실행(POST /admin/batches/{name}). */
object Batches {
    fun run(name: String): Resp = Api.request("POST", "/admin/batches/$name")
}

/** DB 조회(읽기·픽스처 쓰기). */
object Db {
    fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        DriverManager.getConnection(Env.dbUrl, Env.dbUser, Env.dbPassword).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs ->
                    val md = rs.metaData
                    buildList {
                        while (rs.next()) add((1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) })
                    }
                }
            }
        }

    fun update(sql: String, vararg args: Any?): Int =
        DriverManager.getConnection(Env.dbUrl, Env.dbUser, Env.dbPassword).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeUpdate()
            }
        }

    fun long(sql: String, vararg args: Any?): Long = (query(sql, *args).single().values.single() as Number).toLong()
}

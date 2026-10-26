package pub

import support.Api
import support.Clock
import support.Db
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeTest {
    @Test
    fun appAndDbAreReachable() {
        assertEquals(200, Api.get("/actuator/health").status)
        assertTrue(Db.long("SELECT count(*) FROM users") > 0)
        val t = Clock.now()
        assertTrue(Clock.advance(java.time.Duration.ofHours(1)) > t)
    }
}

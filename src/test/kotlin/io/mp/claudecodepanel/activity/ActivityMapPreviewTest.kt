package io.mp.claudecodepanel.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Dev preview: drives the interpreter through a busy, representative session and writes the
 * resulting map to PNGs under `build/` (dark + light) via [ActivityMapRenderer], so the graph can
 * be eyeballed headlessly without launching the IDE. Doubles as smoke coverage for the renderer.
 */
class ActivityMapPreviewTest {

    private var t = Instant.parse("2026-07-19T10:00:00Z")
    private val interp = ActivityInterpreter { t = t.plusSeconds(1); t }
    private val graph = ActivityGraph { t }
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun feed(events: List<AgentActivityEvent>) { events.forEach { graph.apply(it) } }
    private fun read(id: String, path: String) = feed(interp.toolUse(id, "Read", obj("""{"file_path":"$path"}""")))
    private fun edit(id: String, path: String) = feed(interp.toolUse(id, "Edit", obj("""{"file_path":"$path"}""")))

    @Test fun writesMapPreviewImages() {
        feed(interp.taskStarted("Add offline caching to the driver location feature"))
        feed(interp.toolUse("g1", "Grep", obj("""{"pattern":"ViewModel"}""")))
        read("r1", "app/src/main/java/com/x/driver/DriverLocationViewModel.kt")
        read("r2", "app/src/main/java/com/x/data/DriverRepository.kt")
        read("r3", "app/src/main/java/com/x/network/DriverApi.kt")
        read("r4", "app/src/main/java/com/x/ui/DriverScreen.kt")
        read("r5", "app/src/main/java/com/x/db/DriverDao.kt")
        feed(interp.toolUse("s1", "mcp__studio__get_symbol_info", obj("""{"symbolName":"DriverRepository"}""")))
        edit("e1", "app/build.gradle.kts")
        edit("e2", "app/src/main/java/com/x/data/DriverRepository.kt")
        feed(interp.toolUse("w1", "Write", obj("""{"file_path":"app/src/main/java/com/x/db/DriverCacheDao.kt"}""")))
        edit("e3", "app/src/main/java/com/x/driver/DriverLocationViewModel.kt")
        feed(interp.toolUse("b1", "Bash", obj("""{"command":"./gradlew :app:testDebugUnitTest"}""")))
        feed(interp.toolResult("b1", "2 tests completed, 1 failed\ncom.x.DriverLocationViewModelTest > cachesLocations FAILED\nBUILD FAILED", isError = true))
        feed(interp.toolResult("e2", "e: app/src/main/java/com/x/data/DriverRepository.kt: (42, 9): unresolved reference: cache", isError = true))
        edit("e4", "app/src/test/java/com/x/driver/DriverLocationViewModelTest.kt")
        feed(interp.toolUse("f1", "WebFetch", obj("""{"url":"https://developer.android.com/room"}""")))

        val dir = File("build")
        val darkFile = File(dir, "activity-map-preview-dark.png")
        val lightFile = File(dir, "activity-map-preview-light.png")
        ActivityMapRenderer.renderToFile(graph, darkFile, dark = true)
        ActivityMapRenderer.renderToFile(graph, lightFile, dark = false)

        println("[activity-map-preview] wrote ${darkFile.absolutePath}")
        println("[activity-map-preview] wrote ${lightFile.absolutePath}")
        assertTrue("dark preview not written", darkFile.length() > 2000)
        assertTrue("light preview not written", lightFile.length() > 2000)
    }
}

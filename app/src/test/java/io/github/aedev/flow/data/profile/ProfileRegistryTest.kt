package io.github.aedev.flow.data.profile

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ProfileRegistryTest {
    private val dir = createTempDirectory("registry").toFile()
    private val file = File(dir, "datastore/${ProfileRegistry.FILE_NAME}")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun <T> withRegistry(
        newId: () -> String = { error("No profile should be created") },
        block: suspend (ProfileRegistry) -> T,
    ): T =
        runBlocking {
            val job = Job()
            try {
                block(ProfileRegistry(file, CoroutineScope(Dispatchers.IO + job), newId, now = { 1_000L }))
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun `the first run creates Default and makes it active`() {
        val running = withRegistry(newId = { "default-id" }) { it.running() }

        assertThat(running).isEqualTo(RunningProfile(id = "default-id", isDefault = true))
        val state = withRegistry { it.state.first() }
        assertThat(state.profiles).containsExactly(Profile(id = "default-id", createdAt = 1_000L))
        assertThat(state.defaultId).isEqualTo("default-id")
        assertThat(state.activeId).isEqualTo("default-id")
    }

    @Test
    fun `a later start reads the same Default without writing`() {
        withRegistry(newId = { "default-id" }) { it.running() }
        val written = file.readText()

        val running = withRegistry { it.running() }

        assertThat(running).isEqualTo(RunningProfile(id = "default-id", isDefault = true))
        assertThat(file.readText()).isEqualTo(written)
    }

    @Test
    fun `a corrupt file starts over with a new Default`() {
        file.parentFile!!.mkdirs()
        file.writeText("{ not json")

        val running = withRegistry(newId = { "fresh-id" }) { it.running() }

        assertThat(running).isEqualTo(RunningProfile(id = "fresh-id", isDefault = true))
    }

    @Test
    fun `another active profile runs as that profile`() {
        write("""{"profiles":[{"id":"d"},{"id":"study"}],"defaultId":"d","activeId":"study"}""")

        assertThat(withRegistry { it.running() }).isEqualTo(RunningProfile(id = "study", isDefault = false))
    }

    @Test
    fun `an active profile that is gone falls back to Default and keeps the others`() {
        write("""{"profiles":[{"id":"d"},{"id":"study"}],"defaultId":"d","activeId":"deleted"}""")

        val running = withRegistry { it.running() }

        assertThat(running).isEqualTo(RunningProfile(id = "d", isDefault = true))
        assertThat(withRegistry { it.state.first() }.profiles.map { it.id }).containsExactly("d", "study").inOrder()
    }

    @Test
    fun `an active id that could leave its folder runs as Default`() {
        write("""{"profiles":[{"id":"d"},{"id":"../d"}],"defaultId":"d","activeId":"../d"}""")

        assertThat(ProfileRegistry.peek(file)).isNull()
        assertThat(withRegistry { it.running() }).isEqualTo(RunningProfile(id = "d", isDefault = true))
    }

    @Test
    fun `after a restore the app reopens in Default and keeps every profile`() {
        write("""{"profiles":[{"id":"d"},{"id":"study"}],"defaultId":"d","activeId":"study"}""")

        val restored = withRegistry { it.reopenInDefault() }

        assertThat(restored.activeId).isEqualTo("d")
        assertThat(restored.profiles.map { it.id }).containsExactly("d", "study").inOrder()
        assertThat(ProfileRegistry.peek(file)).isEqualTo(RunningProfile(id = "d", isDefault = true))
    }

    @Test
    fun `a restore that brought no registry starts Default`() {
        val restored = withRegistry(newId = { "fresh" }) { it.reopenInDefault() }

        assertThat(restored.defaultId).isEqualTo("fresh")
        assertThat(restored.activeId).isEqualTo("fresh")
    }

    @Test
    fun `a file from a newer version still reads`() {
        write(
            """{"profiles":[{"id":"d","avatar":{"kind":"hologram","frames":3},"pin":"x"}],""" +
                """"defaultId":"d","activeId":"d","postSwitch":true}""",
        )

        val state = withRegistry { it.state.first() }

        assertThat(withRegistry { it.running() }).isEqualTo(RunningProfile(id = "d", isDefault = true))
        assertThat(
            state.profiles
                .single()
                .avatar.kind,
        ).isEqualTo("hologram")
    }

    @Test
    fun `a missing Default is recreated ahead of the other profiles`() {
        val state = ProfileRegistryState(profiles = listOf(Profile(id = "study")), activeId = "study")

        val completed = state.completed(newId = { "d" }, now = { 5L })

        assertThat(completed.profiles.map { it.id }).containsExactly("d", "study").inOrder()
        assertThat(completed.defaultId).isEqualTo("d")
        assertThat(completed.activeId).isEqualTo("study")
    }

    @Test
    fun `a start reads the stored profile straight from the file`() {
        write("""{"profiles":[{"id":"d"},{"id":"study"}],"defaultId":"d","activeId":"study"}""")

        assertThat(ProfileRegistry.peek(file)).isEqualTo(RunningProfile(id = "study", isDefault = false))
    }

    @Test
    fun `a start leaves a missing, corrupt or incomplete file to the store`() {
        assertThat(ProfileRegistry.peek(file)).isNull()
        write("{ not json")
        assertThat(ProfileRegistry.peek(file)).isNull()
        write("""{"profiles":[{"id":"d"}],"defaultId":"d","activeId":"deleted"}""")
        assertThat(ProfileRegistry.peek(file)).isNull()
    }

    @Test
    fun `what the store writes reads back the same straight from the file`() {
        val running = withRegistry(newId = { "default-id" }) { it.running() }

        assertThat(ProfileRegistry.peek(file)).isEqualTo(running)
    }

    private fun write(json: String) {
        file.parentFile!!.mkdirs()
        file.writeText(json)
    }
}

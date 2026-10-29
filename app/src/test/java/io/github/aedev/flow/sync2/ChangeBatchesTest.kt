package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.apply.ReceivedCollection
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.applyBatches
import io.github.aedev.flow.sync2.changes.markSent
import io.github.aedev.flow.sync2.changes.outgoingBatches
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.data.SentDao
import io.github.aedev.flow.sync2.data.SentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeBatchesTest {
    private class MemorySent : SentDao {
        val records = HashMap<Pair<String, String>, SentRecord>()

        override suspend fun forCollection(collection: String) = records.values.filter { it.collection == collection }

        override suspend fun upsert(records: List<SentRecord>) {
            records.forEach { this.records[it.collection to it.recordKey] = it }
        }
    }

    private val head = "ab".repeat(32)

    private fun wire(vararg lines: String) = CollectionWire(lines.toList(), lines.size, "")

    private fun history(vararg titles: Pair<String, String>) =
        mapOf(SyncCollection.WATCH_HISTORY to wire(*titles.map { (id, title) -> """{"title":"$title","videoId":"$id"}""" }.toTypedArray()))

    private suspend fun sentLines(
        sent: MemorySent,
        exported: Map<String, CollectionWire>,
        nowMs: Long = 0,
    ): List<String> {
        val outgoing = outgoingBatches(sent, exported, head, "link-a", nowMs)
        markSent(sent, outgoing)
        return outgoing.batches.flatMap { parseBatch(it.content).lines }
    }

    @Test
    fun only_records_that_changed_since_the_last_upload_go_out() =
        runTest {
            val sent = MemorySent()
            assertEquals(2, sentLines(sent, history("a" to "one", "b" to "two")).size)
            assertEquals(0, sentLines(sent, history("a" to "one", "b" to "two")).size)
            assertEquals(listOf("""{"title":"renamed","videoId":"b"}"""), sentLines(sent, history("a" to "one", "b" to "renamed")))
        }

    @Test
    fun a_batch_names_its_collection_head_and_link() =
        runTest {
            val batch = outgoingBatches(MemorySent(), history("a" to "one"), head, "link-a", 0).batches.single()
            val parsed = parseBatch(batch.content)
            assertEquals(SyncCollection.WATCH_HISTORY, parsed.collection)
            assertEquals(head, parsed.head)
            assertEquals("link-a", parsed.link)
            assertEquals(batch.content.size, parsed.size)
        }

    @Test
    fun large_collections_are_split_into_batches_of_a_thousand() =
        runTest {
            val exported = history(*(1..2500).map { "v$it" to "t" }.toTypedArray())
            val batches = outgoingBatches(MemorySent(), exported, head, "link-a", 0).batches
            assertEquals(listOf(1000, 1000, 500), batches.map { parseBatch(it.content).lines.size })
        }

    @Test
    fun brains_go_out_at_most_every_six_hours_and_ignore_their_export_time() =
        runTest {
            val sent = MemorySent()
            val brain = { hlc: String, weight: Int -> mapOf(SyncCollection.FLOW_NEURO_BRAIN to wire("""{"hlc":"$hlc","w":$weight}""")) }
            val hour = 60 * 60 * 1000L
            assertEquals(1, sentLines(sent, brain("1", 1), nowMs = 0).size)
            assertEquals(0, sentLines(sent, brain("2", 1), nowMs = 7 * hour).size)
            assertEquals(0, sentLines(sent, brain("3", 2), nowMs = 2 * hour).size)
            assertEquals(1, sentLines(sent, brain("4", 2), nowMs = 7 * hour).size)
        }

    @Test
    fun applied_records_are_merged_once_and_not_sent_back() =
        runTest {
            val sent = MemorySent()
            val remote = outgoingBatches(MemorySent(), history(*(1..1200).map { "v$it" to "t" }.toTypedArray()), head, "link-a", 0)
            var applied: Map<String, ReceivedCollection> = emptyMap()
            applyBatches(sent, remote.batches.map { parseBatch(it.content) }, 0) { applied = it }
            assertEquals(1200, applied.getValue(SyncCollection.WATCH_HISTORY).lines.size)
            assertEquals(0, sentLines(sent, history(*(1..1200).map { "v$it" to "t" }.toTypedArray())).size)
        }
}

package io.github.aedev.flow.sync2.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import kotlinx.coroutines.flow.Flow

/** The group this device belongs to, where its store is, and how far it has written and read. */
@Entity(tableName = "group_record")
data class GroupRecord(
    val groupId: String,
    val storeUrl: String,
    val instance: String?,
    val link: String,
    val ownSeq: Long = 0,
    val ownPrev: String = "00".repeat(32),
    val cursor: Long = 0,
    /** The user proved they wrote down the recovery words this device last showed. */
    @ColumnInfo(defaultValue = "0") val recoverySaved: Boolean = false,
    @PrimaryKey val id: Int = 1,
)

/** The verified roster, entry by entry, as canonical JSON. */
@Entity(tableName = "roster")
data class RosterRow(
    @PrimaryKey val seq: Long,
    val entry: String,
)

/** How far this device has read each other device's log. */
@Entity(tableName = "streams")
data class StreamRow(
    @PrimaryKey val did: String,
    val seq: Long,
    val hash: String,
)

@Dao
interface GroupDao {
    @Query("SELECT * FROM group_record WHERE id = 1")
    suspend fun group(): GroupRecord?

    @Query("SELECT * FROM group_record WHERE id = 1")
    fun observeGroup(): Flow<GroupRecord?>

    @Query("UPDATE group_record SET recoverySaved = :saved WHERE id = 1")
    suspend fun setRecoverySaved(saved: Boolean)

    @Upsert
    suspend fun saveGroup(record: GroupRecord)

    @Query("SELECT * FROM roster ORDER BY seq")
    suspend fun roster(): List<RosterRow>

    @Upsert
    suspend fun addRoster(rows: List<RosterRow>)

    @Query("SELECT * FROM streams")
    suspend fun streams(): List<StreamRow>

    @Upsert
    suspend fun saveStreamRows(rows: List<StreamRow>)

    @Query("UPDATE group_record SET cursor = :cursor WHERE id = 1")
    suspend fun saveCursor(cursor: Long)

    /** How far reading got, in one transaction so streams and the cursor never disagree. */
    @Transaction
    suspend fun saveStreams(
        rows: List<StreamRow>,
        cursor: Long,
    ) {
        saveStreamRows(rows)
        saveCursor(cursor)
    }
}

suspend fun GroupDao.addEntries(entries: List<Entry>) = addRoster(entries.map { RosterRow(it.seq, canonicalEntry(it)) })

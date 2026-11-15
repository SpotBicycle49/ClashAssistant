package com.clashassistant.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.clashassistant.model.CardCandidate
import com.clashassistant.model.PlayEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val videoUri: String?,
    val videoDurationSec: Float,
    /** Момент видео (с), в который начался бой. */
    val matchStartVideoSec: Float,
    val eventCount: Int,
)

@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("matchId")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    /** Время видео (с). */
    val videoTimeSec: Float,
    /** Кандидаты в виде "cardId:вероятность;cardId:вероятность". */
    val candidates: String,
    val confirmed: Boolean,
) {
    fun toModel(): PlayEvent = PlayEvent(id, videoTimeSec, decodeCandidates(candidates), confirmed)

    companion object {
        fun fromModel(matchId: Long, e: PlayEvent) = PlayEventEntity(
            matchId = matchId,
            videoTimeSec = e.timeSec,
            candidates = e.candidates.joinToString(";") { "${it.cardId}:${it.probability}" },
            confirmed = e.confirmed,
        )

        fun decodeCandidates(s: String): List<CardCandidate> =
            s.split(';').filter { it.isNotBlank() }.mapNotNull { part ->
                val i = part.lastIndexOf(':')
                if (i <= 0) return@mapNotNull null
                val p = part.substring(i + 1).toFloatOrNull() ?: return@mapNotNull null
                CardCandidate(part.substring(0, i), p)
            }
    }
}

@Dao
abstract class MatchDao {
    @Query("SELECT * FROM matches ORDER BY createdAt DESC")
    abstract fun observeMatches(): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE id = :id")
    abstract suspend fun getMatch(id: Long): MatchEntity?

    @Query("SELECT * FROM play_events WHERE matchId = :matchId ORDER BY videoTimeSec")
    abstract suspend fun getEvents(matchId: Long): List<PlayEventEntity>

    @Upsert
    abstract suspend fun upsertMatch(match: MatchEntity): Long

    @Insert
    abstract suspend fun insertEvents(events: List<PlayEventEntity>)

    @Query("DELETE FROM play_events WHERE matchId = :matchId")
    abstract suspend fun deleteEvents(matchId: Long)

    @Query("DELETE FROM matches WHERE id = :id")
    abstract suspend fun deleteMatch(id: Long)

    /** Сохраняет матч целиком (вместе с событиями). Возвращает id матча. */
    @Transaction
    open suspend fun saveMatch(match: MatchEntity, events: List<PlayEvent>): Long {
        val returned = upsertMatch(match)
        val id = if (match.id != 0L) match.id else returned
        deleteEvents(id)
        insertEvents(events.map { PlayEventEntity.fromModel(id, it) })
        return id
    }
}

@Database(entities = [MatchEntity::class, PlayEventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun matchDao(): MatchDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clash_assistant.db")
                .build().also { instance = it }
        }
    }
}

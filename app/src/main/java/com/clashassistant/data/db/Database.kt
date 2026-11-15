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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.clashassistant.model.CardCandidate
import com.clashassistant.model.IdMethod
import com.clashassistant.model.OpponentCluster
import com.clashassistant.model.PlayEvent
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    /** Фактический эликсир соперника из повтора: "время_видео:эликсир;..." */
    val elixirSeries: String? = null,
    /** Эликсир соперника на 3:00 по данным повтора. */
    val startElixir: Float? = null,
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
    val observedCost: Int? = null,
    val clusterId: Int? = null,
    val method: String? = null,
) {
    fun toModel(): PlayEvent = PlayEvent(
        id = id,
        timeSec = videoTimeSec,
        candidates = decodeCandidates(candidates),
        confirmed = confirmed,
        observedCost = observedCost,
        clusterId = clusterId,
        method = method?.let { runCatching { IdMethod.valueOf(it) }.getOrNull() } ?: IdMethod.MANUAL,
    )

    companion object {
        fun fromModel(matchId: Long, e: PlayEvent) = PlayEventEntity(
            matchId = matchId,
            videoTimeSec = e.timeSec,
            candidates = e.candidates.joinToString(";") { "${it.cardId}:${it.probability}" },
            confirmed = e.confirmed,
            observedCost = e.observedCost,
            clusterId = e.clusterId,
            method = e.method.name,
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

/** Карта колоды соперника из автораспознавания конкретного матча. */
@Entity(
    tableName = "match_clusters",
    primaryKeys = ["matchId", "clusterId"],
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
class ClusterEntity(
    val matchId: Long,
    val clusterId: Int,
    val cardId: String?,
    val cost: Int?,
    val variableCost: Boolean,
    val timesPlayed: Int,
    val thumbnail: ByteArray?,
    val signature: ByteArray,
) {
    fun toModel() = OpponentCluster(clusterId, Signatures.decode(signature), thumbnail, cardId, cost, variableCost, timesPlayed)

    companion object {
        fun fromModel(matchId: Long, c: OpponentCluster) = ClusterEntity(
            matchId, c.id, c.cardId, c.cost, c.variableCost, c.timesPlayed, c.thumbnail, Signatures.encode(c.signature),
        )
    }
}

/** Библиотека образцов: картинка карты «След.» → карта. Пополняется подписями пользователя. */
@Entity(tableName = "card_templates")
class CardTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val signature: ByteArray,
    val createdAt: Long,
)

object Signatures {
    fun encode(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun decode(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
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

    @Query("SELECT * FROM match_clusters WHERE matchId = :matchId ORDER BY clusterId")
    abstract suspend fun getClusters(matchId: Long): List<ClusterEntity>

    @Upsert
    abstract suspend fun upsertMatch(match: MatchEntity): Long

    @Insert
    abstract suspend fun insertEvents(events: List<PlayEventEntity>)

    @Insert
    abstract suspend fun insertClusters(clusters: List<ClusterEntity>)

    @Query("DELETE FROM play_events WHERE matchId = :matchId")
    abstract suspend fun deleteEvents(matchId: Long)

    @Query("DELETE FROM match_clusters WHERE matchId = :matchId")
    abstract suspend fun deleteClusters(matchId: Long)

    @Query("DELETE FROM matches WHERE id = :id")
    abstract suspend fun deleteMatch(id: Long)

    @Query("SELECT * FROM card_templates")
    abstract suspend fun getTemplates(): List<CardTemplateEntity>

    @Insert
    abstract suspend fun insertTemplate(t: CardTemplateEntity): Long

    @Query("SELECT COUNT(*) FROM card_templates")
    abstract fun observeTemplateCount(): Flow<Int>

    @Query("DELETE FROM card_templates")
    abstract suspend fun clearTemplates()

    /** Сохраняет матч целиком (вместе с событиями и картами соперника). Возвращает id матча. */
    @Transaction
    open suspend fun saveMatch(match: MatchEntity, events: List<PlayEvent>, clusters: List<OpponentCluster>): Long {
        val returned = upsertMatch(match)
        val id = if (match.id != 0L) match.id else returned
        deleteEvents(id)
        insertEvents(events.map { PlayEventEntity.fromModel(id, it) })
        deleteClusters(id)
        insertClusters(clusters.map { ClusterEntity.fromModel(id, it) })
        return id
    }
}

@Database(
    entities = [MatchEntity::class, PlayEventEntity::class, ClusterEntity::class, CardTemplateEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun matchDao(): MatchDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** 1 → 2: автораспознавание повторов (стоимость из повтора, карты соперника, библиотека образцов). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE matches ADD COLUMN elixirSeries TEXT")
                db.execSQL("ALTER TABLE matches ADD COLUMN startElixir REAL")
                db.execSQL("ALTER TABLE play_events ADD COLUMN observedCost INTEGER")
                db.execSQL("ALTER TABLE play_events ADD COLUMN clusterId INTEGER")
                db.execSQL("ALTER TABLE play_events ADD COLUMN method TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `match_clusters` (`matchId` INTEGER NOT NULL, `clusterId` INTEGER NOT NULL, " +
                        "`cardId` TEXT, `cost` INTEGER, `variableCost` INTEGER NOT NULL, `timesPlayed` INTEGER NOT NULL, " +
                        "`thumbnail` BLOB, `signature` BLOB NOT NULL, PRIMARY KEY(`matchId`, `clusterId`), " +
                        "FOREIGN KEY(`matchId`) REFERENCES `matches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_match_clusters_matchId` ON `match_clusters` (`matchId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cardId` TEXT NOT NULL, `signature` BLOB NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clash_assistant.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

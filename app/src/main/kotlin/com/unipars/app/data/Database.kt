package com.unipars.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class RecipeStatus {
    ACTIVE,

    /** Waiting for the user to pass a captcha/browser check. */
    NEEDS_HUMAN,

    /** The page changed and the recipe no longer matches. */
    BROKEN,

    /** Network/HTTP errors; retried with backoff. */
    ERROR,
}

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val domain: String,
    /** [com.unipars.engine.Recipe] as JSON. */
    val json: String,
    val status: RecipeStatus = RecipeStatus.ACTIVE,
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastMessage: String? = null,
    val consecutiveFailures: Int = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val bodyHash: String? = null,
    val itemCount: Int = 0,
    val baselineDone: Boolean = false,
    val requestsDay: String? = null,
    val requestsToday: Int = 0,
    /** Re-checked periodically by the maintenance worker; null = unknown. */
    val robotsAllowed: Boolean? = null,
    val robotsCheckedAt: Long? = null,
)

@Entity(tableName = "items", primaryKeys = ["recipeId", "key"])
data class ItemEntity(
    val recipeId: String,
    val key: String,
    val fieldsJson: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val active: Boolean = true,
)

@Entity(tableName = "events", indices = [Index("recipeId"), Index("notified")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val ts: Long,
    val type: String,
    val label: String,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val notified: Boolean = false,
)

@Entity(tableName = "runs", indices = [Index("recipeId")])
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val ts: Long,
    val outcome: String,
    val message: String? = null,
    val durationMs: Long = 0,
    val items: Int = 0,
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val domain: String,
    /** [com.unipars.engine.SiteProfile] as JSON. */
    val json: String,
    val updatedAt: Long,
)

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observe(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes")
    suspend fun all(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE domain = :domain")
    suspend fun byDomain(domain: String): List<RecipeEntity>

    @Upsert
    suspend fun upsert(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE recipeId = :recipeId AND active = 1 ORDER BY firstSeen DESC")
    fun observeActive(recipeId: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE recipeId = :recipeId")
    suspend fun all(recipeId: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE recipeId = :recipeId AND active = 1 LIMIT :limit")
    suspend fun active(recipeId: String, limit: Int): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Query("UPDATE items SET active = 0 WHERE recipeId = :recipeId AND `key` IN (:keys)")
    suspend fun deactivate(recipeId: String, keys: List<String>)

    @Query("DELETE FROM items WHERE recipeId = :recipeId")
    suspend fun deleteFor(recipeId: String)

    @Query("DELETE FROM items WHERE active = 0 AND lastSeen < :before")
    suspend fun deleteInactiveBefore(before: Long): Int
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE recipeId = :recipeId ORDER BY ts DESC, id DESC LIMIT 300")
    fun observe(recipeId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE recipeId = :recipeId AND notified = 0 ORDER BY ts, id")
    suspend fun pending(recipeId: String): List<EventEntity>

    @Insert
    suspend fun insertAll(events: List<EventEntity>)

    @Query("UPDATE events SET notified = 1 WHERE recipeId = :recipeId AND notified = 0")
    suspend fun markNotified(recipeId: String)

    @Query("DELETE FROM events WHERE recipeId = :recipeId")
    suspend fun deleteFor(recipeId: String)

    @Query("DELETE FROM events WHERE ts < :before AND notified = 1")
    suspend fun deleteBefore(before: Long): Int
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs WHERE recipeId = :recipeId ORDER BY ts DESC LIMIT 100")
    fun observe(recipeId: String): Flow<List<RunEntity>>

    @Insert
    suspend fun insert(run: RunEntity)

    @Query("DELETE FROM runs WHERE recipeId = :recipeId AND id NOT IN (SELECT id FROM runs WHERE recipeId = :recipeId ORDER BY ts DESC LIMIT 200)")
    suspend fun trim(recipeId: String)

    @Query("DELETE FROM runs WHERE recipeId = :recipeId")
    suspend fun deleteFor(recipeId: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE domain = :domain")
    suspend fun get(domain: String): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Database(
    entities = [RecipeEntity::class, ItemEntity::class, EventEntity::class, RunEntity::class, ProfileEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipes(): RecipeDao
    abstract fun items(): ItemDao
    abstract fun events(): EventDao
    abstract fun runs(): RunDao
    abstract fun profiles(): ProfileDao

    suspend fun deleteRecipe(id: String) = withTransaction {
        items().deleteFor(id)
        events().deleteFor(id)
        runs().deleteFor(id)
        recipes().delete(id)
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN robotsAllowed INTEGER")
                db.execSQL("ALTER TABLE recipes ADD COLUMN robotsCheckedAt INTEGER")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "unipars.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

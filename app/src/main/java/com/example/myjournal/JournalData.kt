package com.example.myjournal

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class Category(val label: String, val hours: Int) {
    PAIR("Пара", 2), REPLACEMENT("Замена", 2), CONSULTATION("Консультация", 2),
    MASTER("Мастер-класс", 2), OTHER("Другое", 0)
}

@Entity(tableName = "lessons", indices = [Index("date")])
data class Lesson(
    @PrimaryKey val id: String,
    val date: String,
    val title: String,
    val time: String,
    val category: String = Category.PAIR.name,
    val note: String = "",
    val amountKopecks: Long,
    val generated: Boolean = false,
    val overridden: Boolean = false,
    val baseKopecks: Long = 97000,
    val experience: Int = 5,
    @ColumnInfo(defaultValue = "1") val count: Int = 1,
    @ColumnInfo(defaultValue = "''") val endTime: String = ""
)

@Entity(tableName = "schedule_versions")
data class ScheduleVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val year: Int,
    val effectiveDate: String,
    val baseKopecks: Long,
    val experience: Int
)

@Entity(tableName = "schedule_entries", indices = [Index("versionId")], foreignKeys = [
    ForeignKey(entity = ScheduleVersion::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)
])
data class ScheduleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionId: Long = 0,
    val weekday: Int,
    val title: String,
    val time: String
)

@Entity(tableName = "excluded_days")
data class ExcludedDay(@PrimaryKey val date: String)

@Entity(tableName = "cancelled_lessons")
data class CancelledLesson(@PrimaryKey val id: String, val date: String)

@Dao
interface JournalDao {
    @Query("SELECT * FROM lessons ORDER BY date, time") fun observeLessons(): Flow<List<Lesson>>
    @Query("SELECT * FROM schedule_versions ORDER BY effectiveDate, id") fun observeVersions(): Flow<List<ScheduleVersion>>
    @Query("SELECT * FROM schedule_entries ORDER BY weekday, time") fun observeEntries(): Flow<List<ScheduleEntry>>
    @Query("SELECT * FROM excluded_days") fun observeExcluded(): Flow<List<ExcludedDay>>
    @Query("SELECT * FROM lessons") suspend fun lessons(): List<Lesson>
    @Query("SELECT * FROM schedule_versions ORDER BY effectiveDate, id") suspend fun versions(): List<ScheduleVersion>
    @Query("SELECT * FROM schedule_entries") suspend fun entries(): List<ScheduleEntry>
    @Query("SELECT * FROM excluded_days") suspend fun excluded(): List<ExcludedDay>
    @Query("SELECT * FROM cancelled_lessons") suspend fun cancelled(): List<CancelledLesson>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLesson(lesson: Lesson)
    @Insert suspend fun saveVersion(version: ScheduleVersion): Long
    @Insert suspend fun saveEntries(entries: List<ScheduleEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun exclude(day: ExcludedDay)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cancel(lesson: CancelledLesson)
    @Query("DELETE FROM lessons WHERE id = :id") suspend fun deleteLesson(id: String)
    @Query("DELETE FROM excluded_days WHERE date = :date") suspend fun restoreDay(date: String)
    @Query("DELETE FROM lessons WHERE generated = 1 AND overridden = 0 AND date >= :start AND date <= :end")
    suspend fun clearGenerated(start: String, end: String)
}

@Database(entities = [Lesson::class, ScheduleVersion::class, ScheduleEntry::class, ExcludedDay::class, CancelledLesson::class], version = 2, exportSchema = true)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun dao(): JournalDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN count INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE lessons ADD COLUMN endTime TEXT NOT NULL DEFAULT ''")
                db.query("SELECT id, baseKopecks, experience FROM lessons WHERE category = 'MASTER'").use { cursor ->
                    while (cursor.moveToNext()) {
                        val amount = Salary.amount(cursor.getLong(1), cursor.getInt(2), Category.MASTER)
                        db.execSQL("UPDATE lessons SET amountKopecks = ? WHERE id = ?", arrayOf(amount, cursor.getString(0)))
                    }
                }
            }
        }
        fun create(context: Context): JournalDatabase = Room.databaseBuilder(
            context.applicationContext, JournalDatabase::class.java, "my-journal.db"
        ).addMigrations(MIGRATION_1_2).build()
    }
}

object Salary {
    fun experiencePercent(years: Int): Int = if (years == 0) 0 else 10 + (years - 1) * 5
    fun amount(base: Long, years: Int, category: Category, count: Int = 1): Long {
        require(base >= 0 && years >= 0)
        require(count in 1..1000 && (category == Category.MASTER || count == 1))
        val seniority = BigDecimal(100 + experiencePercent(years)).movePointLeft(2)
        val replacement = if (category == Category.REPLACEMENT) BigDecimal("1.15") else BigDecimal.ONE
        return BigDecimal(base).multiply(BigDecimal(category.hours)).multiply(seniority)
            .multiply(replacement).setScale(0, RoundingMode.HALF_UP).longValueExact() * count
    }
    fun afterTax(gross: Long): Long = BigDecimal(gross).multiply(BigDecimal("0.87"))
        .setScale(0, RoundingMode.HALF_UP).longValueExact()
    fun total(lessons: List<Lesson>): Long = afterTax(lessons.filter { it.category != Category.OTHER.name }.sumOf { it.amountKopecks }) +
        lessons.filter { it.category == Category.OTHER.name }.sumOf { it.amountKopecks }
}

fun academicYear(date: LocalDate): Int = if (date.monthValue >= 9) date.year else date.year - 1

class JournalRepository(private val db: JournalDatabase) {
    val dao = db.dao()

    suspend fun saveSchedule(year: Int, from: LocalDate, base: Long, experience: Int, entries: List<ScheduleEntry>) {
        require(!from.isBefore(LocalDate.of(year, 9, 1)) && !from.isAfter(LocalDate.of(year + 1, 5, 31)))
        require(base in 0..1000000000L && experience in 0..80)
        require(entries.all { it.weekday in 1..7 && it.title.isNotBlank() && it.time.matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]")) })
        require(entries.distinctBy { it.weekday to it.time }.size == entries.size)
        db.withTransaction {
            val id = dao.saveVersion(ScheduleVersion(year = year, effectiveDate = from.toString(), baseKopecks = base, experience = experience))
            dao.saveEntries(entries.map { it.copy(id = 0, versionId = id) })
            regenerate(year, from)
        }
    }

    private suspend fun regenerate(year: Int, from: LocalDate) {
        val end = LocalDate.of(year + 1, 5, 31)
        val versions = dao.versions().filter { it.year == year }
        val entries = dao.entries().groupBy { it.versionId }
        val exclusions = dao.excluded().map { it.date }.toSet()
        val cancellations = dao.cancelled().map { it.id }.toSet()
        val overrides = dao.lessons().filter { it.overridden }.map { it.id }.toSet()
        dao.clearGenerated(from.toString(), end.toString())
        var date = from
        while (!date.isAfter(end)) {
            val version = versions.lastOrNull { it.effectiveDate <= date.toString() }
            if (version != null && date.toString() !in exclusions) {
                entries[version.id].orEmpty().filter { it.weekday == date.dayOfWeek.value }.forEach { entry ->
                    // A stable slot key preserves individual cancellations across schedule regeneration.
                    val id = "schedule:${date}:${entry.time}"
                    if (id !in cancellations && id !in overrides) dao.saveLesson(Lesson(
                        id, date.toString(), entry.title, entry.time,
                        amountKopecks = Salary.amount(version.baseKopecks, version.experience, Category.PAIR), generated = true,
                        baseKopecks = version.baseKopecks, experience = version.experience
                    ))
                }
            }
            date = date.plusDays(1)
        }
    }

    suspend fun delete(lesson: Lesson) = db.withTransaction {
        if (lesson.generated) dao.cancel(CancelledLesson(lesson.id, lesson.date))
        dao.deleteLesson(lesson.id)
    }

    suspend fun setExcluded(start: LocalDate, end: LocalDate, excluded: Boolean) = db.withTransaction {
        require(!end.isBefore(start) && academicYear(start) == academicYear(end))
        val existing = dao.lessons().groupBy { it.date }
        var date = start
        while (!date.isAfter(end)) {
            if (excluded) {
                dao.exclude(ExcludedDay(date.toString()))
                // Marking a day non-working makes it empty, including its manually entered lessons.
                existing[date.toString()].orEmpty().forEach { dao.deleteLesson(it.id) }
            } else dao.restoreDay(date.toString())
            date = date.plusDays(1)
        }
        regenerate(academicYear(start), start)
    }
}

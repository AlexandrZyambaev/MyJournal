package com.example.myjournal

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class JournalRepositoryTest {
    private lateinit var db: JournalDatabase
    private lateinit var repository: JournalRepository
    private val start = LocalDate.of(2026, 9, 1)
    private fun monday(time: String = "09:00") = ScheduleEntry(weekday = 1, title = "Программирование", time = time)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JournalDatabase::class.java).build()
        repository = JournalRepository(db)
    }
    @After fun cleanup() { db.close() }

    @Test fun generationIsBoundedAndRepeatedSavingDoesNotDuplicate() = runBlocking {
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday()))
        val first = db.dao().lessons()
        assertEquals(39, first.size)
        assertEquals("2026-09-07", first.minOf { it.date })
        assertEquals("2027-05-31", first.maxOf { it.date })
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday()))
        assertEquals(first, db.dao().lessons())
    }

    @Test fun oneCancellationDoesNotAffectOtherWeeksAndSurvivesRegeneration() = runBlocking {
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday(), monday("10:30")))
        val cancelled = db.dao().lessons().first { it.date == "2026-09-07" && it.time == "09:00" }
        repository.delete(cancelled)
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday(), monday("10:30")))
        val lessons = db.dao().lessons()
        assertEquals(1, lessons.count { it.date == "2026-09-07" })
        assertEquals(2, lessons.count { it.date == "2026-09-14" })
    }

    @Test fun effectiveDatePreservesHistoryAndOverrides() = runBlocking {
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday()))
        val old = db.dao().lessons().first { it.date == "2026-09-07" }
        val edited = db.dao().lessons().first { it.date == "2026-10-05" }.copy(note = "Ручная заметка", overridden = true)
        db.dao().saveLesson(edited)
        val manual = Lesson("manual", "2026-10-05", "Мастер-класс", "14:00", Category.MASTER.name, amountKopecks = 126100)
        db.dao().saveLesson(manual)
        repository.saveSchedule(2026, LocalDate.of(2026, 10, 1), 100000, 5, listOf(monday()))
        val lessons = db.dao().lessons()
        assertEquals(old, lessons.first { it.id == old.id })
        assertEquals(edited, lessons.first { it.id == edited.id })
        assertTrue(lessons.contains(manual))
        assertEquals(260000L, lessons.first { it.date == "2026-10-12" }.amountKopecks)
    }

    @Test fun nonWorkingDayStaysEmptyButAcceptsManualLesson() = runBlocking {
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday()))
        val day = LocalDate.of(2026, 9, 7)
        repository.setExcluded(day, day, true)
        assertFalse(db.dao().lessons().any { it.date == day.toString() })
        val manual = Lesson("manual", day.toString(), "Другое", "12:00", Category.OTHER.name, amountKopecks = 50000)
        db.dao().saveLesson(manual)
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday()))
        assertEquals(listOf(manual), db.dao().lessons().filter { it.date == day.toString() })
        repository.setExcluded(day, day, false)
        assertEquals(2, db.dao().lessons().count { it.date == day.toString() })
    }

    @Test fun databasePersistsAfterReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "journal-persistence-test.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
        val lesson = Lesson("saved", start.toString(), "Сохранено", "09:00", amountKopecks = 252200)
        try {
            disk.dao().saveLesson(lesson)
            disk.close()
            disk = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
            assertEquals(listOf(lesson), disk.dao().lessons())
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun groupedMasterClassesPersistAsOneRecordAndKeepTheirCount() = runBlocking {
        val lesson = Lesson("batch", "2026-10-14", "Кибербезопасность", "09:25", Category.MASTER.name,
            note = "Общая заметка", amountKopecks = Salary.amount(97000, 5, Category.MASTER, 5),
            count = 5, endTime = "14:10")
        db.dao().saveLesson(lesson)
        assertEquals(listOf(lesson), db.dao().lessons())
        repository.saveSchedule(2026, start, 97000, 5, listOf(monday()))
        assertEquals(lesson, db.dao().lessons().first { it.id == "batch" })
        assertEquals(1097070L, Salary.total(listOf(lesson)))
    }

    @Test fun versionOneDatabaseMigratesWithoutLosingRecordsAndCorrectsMasterPayOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "journal-migration-test.db"
        context.deleteDatabase(name)
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema = org.json.JSONObject(assets.open("com.example.myjournal.JournalDatabase/1.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null).use { old ->
            for (i in 0 until schema.length()) {
                val entity = schema.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO lessons VALUES ('master', '2026-10-14', 'Кибербезопасность', '09:25', 'MASTER', 'Заметка', 126101, 0, 0, 97001, 5)")
            old.execSQL("INSERT INTO lessons VALUES ('pair', '2026-10-14', 'Пара', '12:00', 'PAIR', '', 252200, 1, 1, 97000, 5)")
            old.execSQL("INSERT INTO lessons VALUES ('other', '2026-10-14', 'Другое', '15:00', 'OTHER', '', 50000, 0, 0, 97000, 5)")
            old.version = 1
        }
        var migrated = Room.databaseBuilder(context, JournalDatabase::class.java, name)
            .addMigrations(JournalDatabase.MIGRATION_1_2).build()
        try {
            val lessons = migrated.dao().lessons()
            assertEquals(3, lessons.size)
            val master = lessons.first { it.id == "master" }
            assertEquals(252203L, master.amountKopecks)
            assertEquals("Заметка", master.note)
            assertEquals(1, master.count)
            assertEquals("", master.endTime)
            assertEquals(252200L, lessons.first { it.id == "pair" }.amountKopecks)
            assertEquals(50000L, lessons.first { it.id == "other" }.amountKopecks)
            migrated.close()
            migrated = Room.databaseBuilder(context, JournalDatabase::class.java, name)
                .addMigrations(JournalDatabase.MIGRATION_1_2).build()
            assertEquals(master, migrated.dao().lessons().first { it.id == "master" })
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}

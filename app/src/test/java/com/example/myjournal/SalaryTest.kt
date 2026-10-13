package com.example.myjournal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SalaryTest {
    @Test fun fiveYearsPairMatchesExample() {
        assertEquals(252200L, Salary.amount(97000, 5, Category.PAIR))
        assertEquals(219414L, Salary.afterTax(252200))
    }
    @Test fun replacementUsesMultiplicativeBonus() {
        assertEquals(290030L, Salary.amount(97000, 5, Category.REPLACEMENT))
        assertEquals(252326L, Salary.afterTax(290030))
    }
    @Test fun consultationAndMasterHaveExpectedPaidHours() {
        assertEquals(252200L, Salary.amount(97000, 5, Category.CONSULTATION))
        assertEquals(252200L, Salary.amount(97000, 5, Category.MASTER))
    }
    @Test fun fiveMasterClassesArePaidAsFivePairsInOneRecord() {
        val gross = Salary.amount(97000, 5, Category.MASTER, 5)
        assertEquals(1261000L, gross)
        val batch = Lesson("batch", "2026-10-14", "Кибербезопасность", "09:25", Category.MASTER.name,
            amountKopecks = gross, count = 5, endTime = "14:10")
        assertEquals(1097070L, Salary.total(listOf(batch)))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyMasterBatchIsRejected() {
        Salary.amount(97000, 5, Category.MASTER, 0)
    }
    @Test fun zeroAndFirstYearBonuses() {
        assertEquals(0, Salary.experiencePercent(0))
        assertEquals(10, Salary.experiencePercent(1))
        assertEquals(30, Salary.experiencePercent(5))
    }
    @Test fun taxIsRoundedAfterMonthlyAggregation() {
        val lessons = listOf(
            Lesson("1", "2026-09-01", "A", "09:00", amountKopecks = 3),
            Lesson("2", "2026-09-01", "B", "10:30", amountKopecks = 3),
            Lesson("3", "2026-09-01", "Other", "12:00", Category.OTHER.name, amountKopecks = 10000)
        )
        assertEquals(10005L, Salary.total(lessons))
    }
    @Test fun yearSpansSeptemberThroughMay() {
        assertEquals(2026, academicYear(LocalDate.of(2026, 9, 1)))
        assertEquals(2026, academicYear(LocalDate.of(2027, 5, 31)))
    }
}

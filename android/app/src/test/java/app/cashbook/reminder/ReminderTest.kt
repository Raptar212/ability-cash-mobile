package app.cashbook.reminder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderTest {
    private val at21 = LocalTime.of(21, 0)

    @Test fun beforeTimeToday() {
        assertEquals(LocalDateTime.of(2026, 11, 24, 21, 0), Reminder.next(LocalDateTime.of(2026, 11, 24, 9, 30), at21))
    }

    // Будильник сработал ровно в срок — следующий завтра, а не повтор сегодня.
    @Test fun exactlyAtTimeTomorrow() {
        assertEquals(LocalDateTime.of(2026, 11, 25, 21, 0), Reminder.next(LocalDateTime.of(2026, 11, 24, 21, 0), at21))
    }

    @Test fun afterTimeTomorrowAcrossMonth() {
        assertEquals(LocalDateTime.of(2026, 12, 1, 21, 0), Reminder.next(LocalDateTime.of(2026, 11, 30, 23, 59), at21))
    }
}

package ru.company.production.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-тесты календаря расчёта графика.
 *
 * Проверяют чистые функции — отсчёт рабочих дней, пропуск выходных
 * и праздников, межоперационные переходы и отсечку крайнего дня.
 * Логика скопирована из OrderSchedulingService для изоляции.
 */
class OrderSchedulingServiceCalendarTest {

    /* ======================== СТАРТОВЫЙ ДЕНЬ ======================== */

    @Nested
    @DisplayName("Отсчёт даты старта")
    class StartDayTests {

        @Test
        @DisplayName("startDays = 0 → сегодня")
        void startZero() {
            LocalDate today = LocalDate.of(2026, 10, 21); // СРЕДА
            CalendarSnapshot snap = snapshotOf();

            assertEquals(today, plusWorkingDays(today, 0, snap));
        }

        @Test
        @DisplayName("startDays = 1 → следующий рабочий день")
        void startOne() {
            LocalDate today = LocalDate.of(2026, 10, 21); // СРЕДА
            CalendarSnapshot snap = snapshotOf();

            assertEquals(LocalDate.of(2026, 10, 22),
                    plusWorkingDays(today, 1, snap));
        }

        @Test
        @DisplayName("startDays = 1 при пятницу → понедельник")
        void startFriday() {
            LocalDate friday = LocalDate.of(2026, 10, 23);
            CalendarSnapshot snap = snapshotOf();

            assertEquals(LocalDate.of(2026, 10, 26),
                    plusWorkingDays(friday, 1, snap));
        }

        @Test
        @DisplayName("startDays = 2 при пятницу → вторник")
        void startTwoFriday() {
            LocalDate friday = LocalDate.of(2026, 10, 23);
            CalendarSnapshot snap = snapshotOf();

            assertEquals(LocalDate.of(2026, 10, 27),
                    plusWorkingDays(friday, 2, snap));
        }

        @Test
        @DisplayName("startDays = 1 при празднике → пропускает")
        void startOverHoliday() {
            CalendarSnapshot snap = snapshotOf(
                    LocalDate.of(2026, 10, 22));
            LocalDate wed = LocalDate.of(2026, 10, 21);

            assertEquals(LocalDate.of(2026, 10, 23),
                    plusWorkingDays(wed, 1, snap));
        }

        @Test
        @DisplayName("startDays = 3 при празднике → пропускает его")
        void startOverHolidayThree() {
            CalendarSnapshot snap = snapshotOf(
                    LocalDate.of(2026, 10, 22));
            LocalDate wed = LocalDate.of(2026, 10, 21);

            // +1→23(вт), +2→24(ср), +3→25(чт)
            assertEquals(LocalDate.of(2026, 10, 25),
                    plusWorkingDays(wed, 3, snap));
        }
    }

    /* ======================== ПЕРЕХОДЫ ======================== */

    @Nested
    @DisplayName("Межоперационные переходы")
    class TransitionTests {

        @Test
        @DisplayName("transitionDays = 0 → без сдвига")
        void zeroTransition() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate finish = LocalDate.of(2026, 10, 22);

            assertEquals(finish,
                    plusWorkingDays(finish, 0, snap));
        }

        @Test
        @DisplayName("transitionDays = 2 → +2 рабочих дня")
        void twoDaysTransition() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate friday = LocalDate.of(2026, 10, 23);

            // +1→26(ПН), +2→27(ВТ)
            assertEquals(LocalDate.of(2026, 10, 27),
                    plusWorkingDays(friday, 2, snap));
        }
    }

    /* ======================== ОТСЕТКА ======================== */

    @Nested
    @DisplayName("Отсечка крайнего дня изготовления")
    class DeadlineTests {

        @Test
        @DisplayName("minusWorkingDays → шаг назад на N рабочих дней")
        void minusWorkingDays() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate friday = LocalDate.of(2026, 10, 23);

            // -1→22(ПН), -2→21(ВТ), -3→20(СР)
            assertEquals(LocalDate.of(2026, 10, 20),
                    minusWorkingDays(friday, 3, snap));
        }

        @Test
        @DisplayName("minusWorkingDays при понедельнике → пятница")
        void minusOneMonday() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate monday = LocalDate.of(2026, 10, 26);

            // -1 → 23(ПТ)
            assertEquals(LocalDate.of(2026, 10, 23),
                    minusWorkingDays(monday, 1, snap));
        }
    }

    /* ======================== КАЛЕНДАРЬ ======================== */

    @Nested
    @DisplayName("Календарь: выходные и праздники")
    class CalendarTests {

        @Test
        @DisplayName("Суббота не является рабочим днём")
        void saturdayNotWorking() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate saturday = LocalDate.of(2026, 10, 24);

            assertFalse(isWorkingDay(saturday, snap));
        }

        @Test
        @DisplayName("Воскресенье не является рабочим днём")
        void sundayNotWorking() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate sunday = LocalDate.of(2026, 10, 25);

            assertFalse(isWorkingDay(sunday, snap));
        }

        @Test
        @DisplayName("Праздник не является рабочим днём")
        void holidayNotWorking() {
            CalendarSnapshot snap = snapshotOf(
                    LocalDate.of(2026, 10, 22));

            assertFalse(isWorkingDay(LocalDate.of(2026, 10, 22), snap));
        }

        @Test
        @DisplayName("Среда является рабочим днём")
        void wednesdayWorking() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate wed = LocalDate.of(2026, 10, 21);

            assertTrue(isWorkingDay(wed, snap));
        }

        @Test
        @DisplayName("nextWorkingDay пропускает выходные")
        void nextWorkingDaySkipsWeekend() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate saturday = LocalDate.of(2026, 10, 24);

            assertEquals(LocalDate.of(2026, 10, 26),
                    nextWorkingDay(saturday, snap));
        }

        @Test
        @DisplayName("nextWorkingDay пропускает праздник и выходные")
        void nextWorkingDaySkipsHolidayAndWeekend() {
            CalendarSnapshot snap = snapshotOf(
                    LocalDate.of(2026, 10, 22));
            // 22(ПН-праздн)
            LocalDate friday = LocalDate.of(2026, 10, 23);

            // 23(ПТ)→+1→24(СБ)→25(ВС)→26(ПН)
            assertEquals(LocalDate.of(2026, 10, 26),
                    nextWorkingDay(friday, snap));
        }
    }

    /* ======================== СЛОЖНЫЕ СЦЕНАРИИ ======================== */

    @Nested
    @DisplayName("Сложные сценарии календаря")
    class ComplexCalendarTests {

        @Test
        @DisplayName("Праздник в субботу не влияет на рабочий день")
        void holidayOnSaturday() {
            CalendarSnapshot snap = snapshotOf(
                    LocalDate.of(2026, 10, 24)); // СБ — праздник
            LocalDate friday = LocalDate.of(2026, 10, 23);

            // 23(ПТ)→+1→24(СБ, праздник но и так не рабочий)→25(ВС)→26(ПН)
            assertEquals(LocalDate.of(2026, 10, 26),
                    plusWorkingDays(friday, 1, snap));
        }

        @Test
        @DisplayName("Серия из 5 рабочих дней: ПН→ПТ")
        void fiveWorkingDays() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate monday = LocalDate.of(2026, 10, 26);

            assertEquals(LocalDate.of(2026, 10, 30),
                    plusWorkingDays(monday, 4, snap));
        }

        @Test
        @DisplayName("Последовательное ±N дней возвращает к исходной")
        void roundTrip() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate wed = LocalDate.of(2026, 10, 21);

            LocalDate after = plusWorkingDays(wed, 5, snap);
            LocalDate back = minusWorkingDays(after, 5, snap);

            assertEquals(wed, back);
        }

        @Test
        @DisplayName("nextWorkingDay на рабочий день → тот же день")
        void nextWorkingDayOnWorkday() {
            CalendarSnapshot snap = snapshotOf();
            LocalDate wed = LocalDate.of(2026, 10, 21);

            assertEquals(wed, nextWorkingDay(wed, snap));
        }
    }

    /* ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================== */

    private CalendarSnapshot snapshotOf() {
        return snapshotOf(Set.of());
    }

    private CalendarSnapshot snapshotOf(LocalDate... holidays) {
        return snapshotOf(new HashSet<>(Arrays.asList(holidays)));
    }

    private CalendarSnapshot snapshotOf(Set<LocalDate> holidays) {
        return new CalendarSnapshot(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                holidays,
                Map.of()
        );
    }

    private static boolean isWorkingDay(LocalDate date, CalendarSnapshot snap) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !snap.holidays().contains(date);
    }

    private static LocalDate nextWorkingDay(LocalDate date, CalendarSnapshot snap) {
        LocalDate candidate = date;
        int guard = 0;
        while (!isWorkingDay(candidate, snap) && guard++ < 400) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static LocalDate plusWorkingDays(LocalDate from, int workingDays, CalendarSnapshot snap) {
        LocalDate candidate = nextWorkingDay(from, snap);
        for (int day = 0; day < workingDays; day++) {
            candidate = nextWorkingDay(candidate.plusDays(1), snap);
        }
        return candidate;
    }

    private LocalDate minusWorkingDays(LocalDate from, int workingDays, CalendarSnapshot snap) {
        LocalDate candidate = previousWorkingDay(from);
        for (int day = 0; day < workingDays; day++) {
            candidate = previousWorkingDay(candidate.minusDays(1));
        }
        return candidate;
    }

    private static LocalDate previousWorkingDay(LocalDate date) {
        LocalDate candidate = date;
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /* Снимок календаря — копия из OrderSchedulingService. */
    private record CalendarSnapshot(
            LocalDate windowFrom,
            LocalDate windowTo,
            Set<LocalDate> holidays,
            Map<LocalDate, Set<Long>> absencesByDate
    ) {
        Set<Long> absentOn(LocalDate date) {
            return absencesByDate.getOrDefault(date, Set.of());
        }
    }
}

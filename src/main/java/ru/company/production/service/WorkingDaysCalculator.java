package ru.company.production.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Расчёт рабочих дней (понедельник — пятница).

 * Все параметры приоритета — «через сколько дней», «сколько дней на
 * изготовление» — считаются в рабочих днях, потому что производство
 * в выходные не работает, и календарные дни дали бы срок раньше или
 * позже фактического.
 *
 * Производственный календарь с переносами и праздниками в системе
 * не ведётся: при его появлении достаточно добавить источник дат
 * в этот класс, вызывающий код менять не придётся.
 */
@Component
public class WorkingDaysCalculator {

    /**
     * Дата через количество рабочих дней после указанной.
     * Нулевой шаг возвращает ту же дату, если она рабочая,
     * иначе — ближайший рабочий день вперёд.
     */
    public LocalDate plus(LocalDate date, int workingDays) {
        if (date == null) {
            return null;
        }

        LocalDate result = nextWorkingDay(date);

        for (int day = 0; day < workingDays; day++) {
            result = result.plusDays(1);
            result = nextWorkingDay(result);
        }

        return result;
    }

    /**
     * Дата за указанное количество рабочих дней до даты.
     * Нулевой шаг возвращает ту же дату, если она рабочая,
     * иначе — ближайший рабочий день назад.
     */
    public LocalDate minus(LocalDate date, int workingDays) {
        if (date == null) {
            return null;
        }

        LocalDate result = previousWorkingDay(date);

        for (int day = 0; day < workingDays; day++) {
            result = result.minusDays(1);
            result = previousWorkingDay(result);
        }

        return result;
    }

    public LocalDate nextWorkingDay(LocalDate date) {
        LocalDate result = date;

        while (isWeekend(result)) {
            result = result.plusDays(1);
        }

        return result;
    }

    private LocalDate previousWorkingDay(LocalDate date) {
        LocalDate result = date;

        while (isWeekend(result)) {
            result = result.minusDays(1);
        }

        return result;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();

        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}

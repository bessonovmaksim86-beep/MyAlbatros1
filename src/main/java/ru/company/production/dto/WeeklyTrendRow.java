package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Точка недельного тренда план/факт.
 *
 * @param weekNumber       номер недели
 * @param weekFrom         начало недели (понедельник)
 * @param weekTo           конец недели (пятница)
 * @param plannedCount     плановых операций за неделю
 * @param actualCount      факт выполненных операций за неделю
 * @param plannedBarPercent ширина планового сегмента бара в % (0..100)
 * @param actualBarPercent  ширина фактического сегмента бара в % (0..100)
 */
public record WeeklyTrendRow(
        int weekNumber,
        LocalDate weekFrom,
        LocalDate weekTo,
        long plannedCount,
        long actualCount,
        double plannedBarPercent,
        double actualBarPercent
) {
}

package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Недельная строка планового расчёта.
 *
 * @param weekNumber       номер недели
 * @param weekFrom         начало недели
 * @param weekTo           конец недели
 * @param laborHours       трудоёмкость за неделю, ч
 * @param capacityHours    доступная мощность за неделю, ч
 * @param loadPercent      загрузка в %
 * @param barPercent       ширина бара в % (0..100)
 */
public record ForecastWeekRow(
        int weekNumber,
        LocalDate weekFrom,
        LocalDate weekTo,
        BigDecimal laborHours,
        BigDecimal capacityHours,
        double loadPercent,
        double barPercent
) {
}

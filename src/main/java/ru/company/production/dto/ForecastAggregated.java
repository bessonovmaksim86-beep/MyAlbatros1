package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Агрегированный результат планового расчёта для нескольких изделий.
 *
 * @param totalQty               суммарное количество
 * @param finishDate             расчётная дата завершения
 *                             (по последней позиции)
 * @param totalLaborHours        суммарная трудоёмкость, нормо-ч
 * @param limitingClassifierName изделие, которое лимитирует срок
 * @param limitingOperation      лимитирующая операция
 * @param limitingHours          трудоёмкость лимитирующей операции
 * @param totalWorkingDays       рабочих дней до завершения
 * @param feasible               выполним ли в пределах горизонта
 */
public record ForecastAggregated(
        int totalQty,
        LocalDate finishDate,
        BigDecimal totalLaborHours,
        String limitingClassifierName,
        String limitingOperation,
        BigDecimal limitingHours,
        int totalWorkingDays,
        boolean feasible
) {
}

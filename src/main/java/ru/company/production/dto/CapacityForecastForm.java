package ru.company.production.dto;

import java.time.LocalDate;

/**
 * Форма планового расчёта.
 *
 * @param classifierId идентификатор изделия
 * @param quantity     количество штук
 * @param startDate    дата начала производства
 * @param priorityId   приоритет планирования (справочник order_priorities)
 */
public record CapacityForecastForm(
        Long classifierId,
        int quantity,
        LocalDate startDate,
        Long priorityId
) {
}

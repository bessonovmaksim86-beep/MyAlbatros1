package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Форма планового расчёта.
 *
 * @param classifierId идентификатор изделия
 * @param quantity     количество штук
 * @param startDate    дата начала производства
 */
public record CapacityForecastForm(
        Long classifierId,
        int quantity,
        LocalDate startDate
) {
}

package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Загрузка за один рабочий день.
 */
public record CapacityDayRow(
        LocalDate date,
        int dayOfWeek,
        BigDecimal capacityHours,
        BigDecimal confirmedHours,
        BigDecimal draftHours,
        double loadPercent,
        boolean isHoliday
) {
    public boolean isCritical() {
        return !isHoliday && loadPercent > 100;
    }

    public boolean isWarning() {
        return !isHoliday && loadPercent >= 85 && loadPercent <= 100;
    }
}

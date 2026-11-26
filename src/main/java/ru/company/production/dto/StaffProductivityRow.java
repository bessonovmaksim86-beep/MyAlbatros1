package ru.company.production.dto;

import java.math.BigDecimal;

/**
 * Строка статистики производительности сотрудника.
 */
public record StaffProductivityRow(
        Long employeeId,
        String fullName,
        BigDecimal plannedHours,
        BigDecimal factHours,
        BigDecimal unassignedHours,
        BigDecimal workedDays,
        BigDecimal productivity
) {}

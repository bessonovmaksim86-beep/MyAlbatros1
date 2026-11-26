package ru.company.production.dto;

import java.math.BigDecimal;

/**
 * Строка статистики «сотрудник × операция».
 */
public record StaffOperationRow(
        Long employeeId,
        String employeeName,
        String operationName,
        Long completedCount,
        BigDecimal totalHours
) {}

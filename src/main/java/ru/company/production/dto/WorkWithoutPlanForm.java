package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Форма ввода работы без плана.
 */
public record WorkWithoutPlanForm(
        Long employeeId,
        String operationName,
        BigDecimal normHours,
        Integer quantity,
        LocalDate workDate
) {}

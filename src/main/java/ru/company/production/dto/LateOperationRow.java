package ru.company.production.dto;

import java.time.LocalDate;

/**
 * Отдельная просроченная операция.
 *
 * @param serialNumber  полный серийный номер изделия (код+серия)
 * @param operationName наименование операции
 * @param unitName      название цеха
 * @param executorName  ФИО исполнителя
 * @param plannedDate   плановая дата
 * @param actualDate    фактическая дата завершения
 * @param lateDays      дней просрочки (целых)
 */
public record LateOperationRow(
        String serialNumber,
        String operationName,
        String unitName,
        String executorName,
        LocalDate plannedDate,
        LocalDate actualDate,
        long lateDays
) {
}

package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Сводка планового расчёта — «заказ будет выполнен до …».
 *
 * @param classifierId      идентификатор изделия
 * @param classifierCode    код классификатора
 * @param classifierName    наименование
 * @param productTypeName   тип изделия
 * @param quantity          количество штук
 * @param startDate         дата начала (с учётом запуска приоритета)
 * @param finishDate        расчётная дата завершения; null — срок
 *                          определить нельзя (нет мощностей)
 * @param totalLaborHours   суммарная трудоёмкость, нормо-ч
 * @param totalWorkingDays  рабочих дней до завершения (минимум 1)
 * @param feasible          выполним ли в пределах горизонта
 * @param limitingOperation лимитирующая (последняя) операция
 * @param limitingHours     трудоёмкость лимитирующей операции
 * @param priorityCode      код приоритета
 * @param priorityName      название приоритета
 * @param priorityBadgeClass CSS-класс бейджа приоритета
 * @param priorityStartDays задержка запуска, рабочих дней
 * @param priorityTransitionDays пауза между операциями, рабочих дней
 * @param priorityMaxLoadPercent допустимая загрузка цеха, %
 * @param missingCapacityOperations операций без мощности (нет цеха
 *                          или сотрудников) — срок недостоверен
 */
public record ForecastSummary(
        Long classifierId,
        Integer classifierCode,
        String classifierName,
        String productTypeName,
        int quantity,
        LocalDate startDate,
        LocalDate finishDate,
        BigDecimal totalLaborHours,
        int totalWorkingDays,
        boolean feasible,
        String limitingOperation,
        BigDecimal limitingHours,
        String priorityCode,
        String priorityName,
        String priorityBadgeClass,
        int priorityStartDays,
        int priorityTransitionDays,
        int priorityMaxLoadPercent,
        int missingCapacityOperations
) {
}

package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Сводка планового расчёта — «заказ будет выполнен до …».
 *
 * @param classifierId      идентификатор изделия
 * @param classifierCode    код классификатора
 * @param classifierName    наименование
 * @param productTypeName   тип изделия
 * @param quantity          количество штук
 * @param startDate         дата начала
 * @param finishDate        расчётная дата завершения
 * @param totalLaborHours   суммарная трудоёмкость, нормо-ч
 * @param totalWorkingDays  рабочих дней до завершения
 * @param feasible          выполним ли в пределах горизонта
 * @param limitingOperation лимитирующая (последняя) операция
 * @param limitingHours     трудоёмкость лимитирующей операции
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
        BigDecimal limitingHours
) {
}

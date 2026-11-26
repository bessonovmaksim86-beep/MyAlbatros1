package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Статистика фактического выполнения операций по производственному
 * подразделению (цеху) за выбранный период.
 *
 * Поля заполняются сервисом на основе двух запросов: факт —
 * {@code aggregateByUnit} из ProductSerialOperationRepository,
 * план — {@code aggregatePlannedByUnit} из OrderScheduleRowRepository.
 *
 * @param unitId          идентификатор подразделения
 * @param unitName        название цеха (или «Без подразделения»)
 * @param serviceCode     код сервиса (производственной службы)
 * @param serviceName     название сервиса
 * @param workingDays     рабочих дней в периоде
 * @param doneOperations  завершённых операций
 * @param uniqueSerials   уникальных изделий в работе
 * @param lateOperations  завершены позже плановой даты
 * @param reworkOperations повторные (переделки)
 * @param employees       численность подразделения
 * @param donePerDay      операций в средний рабочий день
 * @param plannedHoursPerDay нормо-часов в средний рабочий день
 * @param utilizationPercent факт / план, × (null если план пуст)
 */
public record UnitOperationStats(
        Long unitId,
        String unitName,
        String serviceCode,
        String serviceName,
        int workingDays,
        long doneOperations,
        long uniqueSerials,
        long lateOperations,
        long reworkOperations,
        long employees,
        BigDecimal donePerDay,
        BigDecimal plannedHoursPerDay,
        BigDecimal utilizationPercent
) {
}

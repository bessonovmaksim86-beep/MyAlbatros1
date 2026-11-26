package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Строка маршрута операций в плановом расчёте.
 *
 * @param position              позиция в маршруте
 * @param operationCode         код операции
 * @param operationName         наименование
 * @param operationTypeName     тип операции
 * @param unitName              название цеха
 * @param serviceCode           код сервиса
 * @param laborHours            норма на 1 изделие, ч
 * @param machineHours          машинное время на 1 изделие, ч
 * @param dailyLimit            пропускная способность оборудования (выполнений/день)
 * @param hoursForQty           трудоёмкость на заказ, ч
 * @param capacityEmployees     сотрудников в цехе
 * @param dailyCapacityHours    мощность цеха (сотрудники × 8), ч/день
 * @param effectiveDailyLimitHours  эффективный потолок (учтён daily_limit), ч/день
 * @param start                 дата начала
 * @param finish                дата окончания
 * @param loadPercent           загрузка в % (от мощности)
 * @param limiting              лимитирующая операция
 * @param bottleneckHint        подсказка об узком месте
 */
public record ForecastOperationRow(
        int position,
        String operationCode,
        String operationName,
        String operationTypeName,
        String unitName,
        String serviceCode,
        BigDecimal laborHours,
        BigDecimal machineHours,
        int dailyLimit,
        BigDecimal hoursForQty,
        int capacityEmployees,
        BigDecimal dailyCapacityHours,
        BigDecimal effectiveDailyLimitHours,
        LocalDate start,
        LocalDate finish,
        double loadPercent,
        boolean limiting,
        String bottleneckHint
) {
}

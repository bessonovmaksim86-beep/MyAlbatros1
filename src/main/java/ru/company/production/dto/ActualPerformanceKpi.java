package ru.company.production.dto;

import java.math.BigDecimal;

/**
 * KPI-карточки фактической производительности за период.
 *
 * @param totalOperations       выполненные операции
 * @param totalSerials          уникальных изделий в работе
 * @param totalLaborHours       трудозатраты по завершённым операциям, нормо-ч
 * @param lateOperations        из них просрочены (факт позже плана)
 * @param reworkOperations      из них переделаны (attempt > 1)
 * @param totalUnitsProduced    готовых ячеек, оформленных за период
 */
public record ActualPerformanceKpi(
        long totalOperations,
        long totalSerials,
        BigDecimal totalLaborHours,
        long lateOperations,
        long reworkOperations,
        long totalUnitsProduced
) {
}

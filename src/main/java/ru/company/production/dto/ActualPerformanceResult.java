package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Сводный результат анализа фактической производительности.
 *
 * @param kpi             KPI-карточки
 * @param unitStats       статистика по цехам
 * @param weeklyTrend     тренд по неделям
 * @param productionOutput выпуск по изделиям
 * @param executorRanking рейтинг исполнителей
 * @param lateOperations  просроченные операции
 * @param weeks           ширина окна в неделях
 * @param dateFrom        начальная дата периода
 * @param dateTo          конечная дата периода
 */
public record ActualPerformanceResult(
        ActualPerformanceKpi kpi,
        List<UnitOperationStats> unitStats,
        List<WeeklyTrendRow> weeklyTrend,
        List<ProductionOutputRow> productionOutput,
        List<ExecutorRankingRow> executorRanking,
        List<LateOperationRow> lateOperations,
        int weeks,
        LocalDate dateFrom,
        LocalDate dateTo
) {
}

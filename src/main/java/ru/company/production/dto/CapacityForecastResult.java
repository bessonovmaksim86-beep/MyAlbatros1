package ru.company.production.dto;

import java.util.List;

/**
 * Итоговый результат планового расчёта.
 *
 * @param summary    сводка (заказ будет выполнен до …)
 * @param operations маршруты операций
 * @param weeklyPlan план по неделям
 * @param warnings   предупреждения
 * @param feasible   выполним ли в пределах горизонта
 */
public record CapacityForecastResult(
        ForecastSummary summary,
        List<ForecastOperationRow> operations,
        List<ForecastWeekRow> weeklyPlan,
        List<String> warnings,
        boolean feasible
) {
}

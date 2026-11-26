package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Сводка загрузки одного подразделения за горизонт планирования.
 *
 * Часы разделены на {@code confirmedHours} (внесённый план) и
 * {@code draftHours} (предварительные расчёты). Загруженность
 * строится по сумме, но визуально сегменты разделены.
 */
public record CapacityUnitSummary(
        Long unitId,
        String unitCode,
        String unitName,
        String serviceCode,
        String serviceName,
        int maxWeeks,
        List<CapacityWeekRow> weeks,
        BigDecimal totalCapacityHours,
        BigDecimal confirmedHours,
        BigDecimal draftHours,
        BigDecimal totalLoadHours,
        double loadPercent,
        double freeHours,
        int criticalCount,
        int warningCount,
        int deficitDays
) {
    public boolean isCritical() {
        return loadPercent > 100 || criticalCount > 0;
    }

    public boolean isWarning() {
        return loadPercent >= 85 && loadPercent <= 100;
    }

    /** Процент занятости подтверждённым планом. */
    public double confirmedPercent() {
        if (totalCapacityHours == null || totalCapacityHours.signum() <= 0) {
            return confirmedHours != null && confirmedHours.signum() > 0
                    ? 100.0
                    : 0.0;
        }
        return confirmedHours.multiply(BigDecimal.valueOf(100))
                .divide(totalCapacityHours, 1, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** Процент предварительных черновиков. */
    public double draftPercent() {
        if (totalCapacityHours == null || totalCapacityHours.signum() <= 0) {
            return 0.0;
        }
        return draftHours.multiply(BigDecimal.valueOf(100))
                .divide(totalCapacityHours, 1, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    /*
     * Ширины сегментов полосы. Считаются здесь, а не в шаблоне:
     * сумма «план + черновик» не должна вылезти за 100 %, иначе
     * сегмент уедет за край полосы.
     */

    public double confirmedBarPercent() {
        return Math.min(100.0, Math.max(0.0, confirmedPercent()));
    }

    public double draftBarPercent() {
        return Math.min(
                100.0 - confirmedBarPercent(),
                Math.max(0.0, draftPercent())
        );
    }
}

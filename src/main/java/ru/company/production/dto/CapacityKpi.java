package ru.company.production.dto;

/**
 * Сводка для KPI-блока.
 */
public record CapacityKpi(
        java.math.BigDecimal totalCapacityHours,
        java.math.BigDecimal totalLoadHours,
        double totalLoadPercent,
        double freeHours,
        int criticalWeeks,
        int warningWeeks,
        int totalUnassigned,
        int deficitDays
) {}

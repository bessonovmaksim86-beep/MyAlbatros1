package ru.company.production.dto;

/**
 * Операция, требующая назначения: без цеха или без исполнителей.
 *
 * {@code plannedHours} — часы, уже попавшие в график (план + черновики),
 * по строкам, у которых подразделение не задано либо операция
 * не закреплена за людьми.
 */
public record UnassignedOperation(
        Long operationId,
        String operationName,
        String operationTypeName,
        String serviceCode,
        String serviceName,
        int routesCount,
        String routeCodes,
        int plannedHours,
        int availableEmployees,
        String hint
) {}

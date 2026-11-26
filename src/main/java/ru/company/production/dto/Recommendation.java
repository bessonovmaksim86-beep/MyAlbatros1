package ru.company.production.dto;

/**
 * Рекомендация для блока "требует внимания".
 */
public record Recommendation(
        Severity severity,
        String unitName,
        String message,
        String actionHint
) {
    public enum Severity {
        CRITICAL, WARNING, OPPORTUNITY
    }
}

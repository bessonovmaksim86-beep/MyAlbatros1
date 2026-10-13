package ru.company.production.repository;

/**
 * Строка глобального поиска среди операций.
 */
public interface OperationSearchView {

    Long getId();

    String getOperationName();

    String getOperationTypeCode();

    String getOperationTypeName();
}

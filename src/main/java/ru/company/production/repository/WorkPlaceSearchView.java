package ru.company.production.repository;

/**
 * Строка глобального поиска среди рабочих мест.
 */
public interface WorkPlaceSearchView {

    Long getId();

    String getCode();

    String getName();

    String getPlaceTypeCode();

    String getPlaceTypeName();
}

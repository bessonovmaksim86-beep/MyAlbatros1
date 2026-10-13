package ru.company.production.repository;

/**
 * Строка глобального поиска среди техпроцессов.
 *
 * Подпись маршрута строится по изделию основного маршрута, поэтому
 * изделие выбирается через productClassifier: маршрут, применённый
 * к нескольким изделиям, не должен превращаться в несколько строк
 * поиска.
 */
public interface TechProcessSearchView {

    Long getId();

    String getCode();

    Integer getClassifierCode();

    String getClassifierName();

    String getSensorClassName();
}

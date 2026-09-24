package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Строка списка техпроцессов: техпроцесс вместе с одним из изделий,
 * к которым он применён. Если маршрут назначен нескольким изделиям,
 * он отображается отдельной строкой для каждого из них.
 */
public interface TechProcessListItemView {

    Long getId();

    String getCode();

    Long getClassifierId();

    Integer getClassifierCode();

    ProductType getProductType();

    String getClassifierName();

    String getSensorClassName();

    /**
     * Пометка «базовый»: из нескольких маршрутов одного изделия
     * базовый показывают первым и с меткой.
     */
    boolean isBasic();

    long getOperationCount();
}

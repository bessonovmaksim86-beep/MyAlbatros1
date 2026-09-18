package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Уплощённые данные изделия для каскадного выбора:
 * тип изделия, класс датчика и наименование.
 */
public interface ClassifierOptionProjection {

    Long getId();

    Integer getCode();

    ProductType getProductType();

    String getName();

    Long getSensorClassId();

    String getSensorClassName();
}

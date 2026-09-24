package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Изделие, к которому применён техпроцесс, вместе с пометкой о
 * базовости: тот же маршрут может быть для одного изделия основным,
 * а для другого — обычным.
 */
public interface AppliedClassifierView {

    Long getClassifierId();

    Integer getCode();

    ProductType getProductType();

    String getName();

    String getSensorClassName();

    boolean isBasic();
}

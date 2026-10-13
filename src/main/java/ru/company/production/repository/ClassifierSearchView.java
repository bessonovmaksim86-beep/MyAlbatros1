package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Строка глобального поиска среди изделий классификатора.
 *
 * Род датчика отдаётся отдельным полем: у изделия типа «Датчик»
 * наименования нет, и показывать нужно именно наименование из
 * справочника родов.
 */
public interface ClassifierSearchView {

    Long getId();

    Integer getCode();

    ProductType getProductType();

    String getName();

    String getSensorClassName();
}

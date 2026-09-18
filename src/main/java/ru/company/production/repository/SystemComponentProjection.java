package ru.company.production.repository;

/**
 * Строка состава системы для раскрытия полей выбора
 * датчиков и приборов в форме заказа покупателя.
 *
 * Заполнена ровно одна пара полей: род датчика (sensorCatalog)
 * либо прибор-классификатор (componentClassifier).
 */
public interface SystemComponentProjection {

    Long getSensorCatalogId();

    String getSensorCatalogName();

    Long getComponentClassifierId();

    Integer getComponentCode();

    String getComponentName();
}

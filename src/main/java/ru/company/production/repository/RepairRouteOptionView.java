package ru.company.production.repository;

/**
 * Строка выбора маршрута в заказе на ремонт силами СДП.
 *
 * Проектная строка (Spring Data читает по именам-алиасам), а не сущность:
 * форме нужны только код маршрута и изделие, к которому он разработан,
 * — остальное тянуло бы за собой ленивые коллекции операций.
 */
public interface RepairRouteOptionView {

    Long getId();

    String getCode();

    Integer getClassifierCode();

    String getClassifierName();
}

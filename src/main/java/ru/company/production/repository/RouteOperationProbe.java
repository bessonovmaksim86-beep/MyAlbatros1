package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Операция в составе действующего маршрута с её подразделением и
 * изделием — строка-проба для отчёта о неприсвоенных операциях.
 */
public interface RouteOperationProbe {

    Long getOperationId();

    String getOperationName();

    String getOperationTypeName();

    String getUnitName();

    String getProcessCode();

    Integer getClassifierCode();

    String getClassifierName();

    String getSensorName();

    ProductType getProductType();
}

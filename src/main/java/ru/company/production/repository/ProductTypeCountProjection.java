package ru.company.production.repository;

import ru.company.production.entity.ProductType;

public interface ProductTypeCountProjection {
    ProductType getProductType();
    Long getTotal();
}

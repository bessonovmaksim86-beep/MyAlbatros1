package ru.company.production.repository;

import ru.company.production.entity.ProductType;

import java.math.BigDecimal;

/**
 * Суммарное заказанное количество продукции по типу изделия.
 *
 * В отличие от ProductTypeCountProjection (число записей
 * классификатора) здесь складывается quantity позиций заказов.
 */
public interface ProductTypeQuantityProjection {

    ProductType getProductType();

    BigDecimal getTotalQuantity();
}

package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид связи между двумя конкретными экземплярами изделий.
 *
 * Связь всегда направлена: родитель — изделие, в которое устанавливают
 * (ячейка или система), потомок — устанавливаемое (прибор или датчик).
 */
@Getter
@RequiredArgsConstructor
public enum ItemLinkType {

    CELL_TO_DEVICE("Ячейка — прибор", ProductType.CELL, ProductType.DEVICE),
    CELL_TO_SENSOR("Ячейка — датчик", ProductType.CELL, ProductType.SENSOR),
    SYSTEM_TO_CELL("Система — ячейка", ProductType.SYSTEM, ProductType.CELL),
    SYSTEM_TO_DEVICE("Система — прибор", ProductType.SYSTEM, ProductType.DEVICE),
    SYSTEM_TO_SENSOR("Система — датчик", ProductType.SYSTEM, ProductType.SENSOR);

    private final String displayName;

    /** Тип изделия-родителя, допустимый для этой связи. */
    private final ProductType parentType;

    /** Тип изделия-потомка, допустимый для этой связи. */
    private final ProductType childType;

    /**
     * Подходит ли пара типов для этого вида связи.
     * Используется при проверке перед сохранением.
     */
    public boolean matches(ProductType parent, ProductType child) {
        return parentType == parent && childType == child;
    }
}

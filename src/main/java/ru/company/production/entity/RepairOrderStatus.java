package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус заказа на ремонт (ЗНП).
 *
 * Набор короткий и отличается от {@link ProductionOrderStatus}: у
 * заказа на ремонт нет «частично выполнен» — ремонт ячейки принимают
 * целиком по акту, а не по числу отремонтированных экземпляров.
 */
@Getter
@RequiredArgsConstructor
public enum RepairOrderStatus {

    NEW("Сформирован", "draft"),

    IN_WORK("В работе", "work"),

    DONE("Выполнен", "done"),

    CANCELLED("Отменён", "deleted");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}

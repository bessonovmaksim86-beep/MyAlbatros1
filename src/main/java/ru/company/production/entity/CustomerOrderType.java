package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид заказа покупателя.
 *
 * Определяет назначение заказа: производство новой продукции,
 * ремонт (по гарантии или платно) либо внутренний заказ отдела.
 * По умолчанию заказ создаётся как производственный.
 */
@Getter
@RequiredArgsConstructor
public enum CustomerOrderType {

    PRODUCTION("Производство", "type-production"),
    WARRANTY_REPAIR("Гарантийный ремонт", "type-warranty"),
    PAID_REPAIR("Платный ремонт", "type-paid"),
    INTERNAL("Внутренний заказ", "type-internal");

    private final String displayName;

    /** Модификатор CSS-класса бейджа вида заказа. */
    private final String badgeClass;

    /** Вид по умолчанию: новый заказ считается производственным. */
    public static CustomerOrderType orDefault(CustomerOrderType type) {
        return type == null ? PRODUCTION : type;
    }
}

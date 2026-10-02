package ru.company.production.repository;

import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionItemStatus;

/**
 * Строка состава заказа на производство — один выпускаемый экземпляр.
 *
 * Полный номер изделия собирается на сервере (getFullNumber()):
 * 4 цифры кода классификатора + 5 цифр серийного номера.
 */
public interface ProductionOrderItemView {

    Long getId();

    Integer getPosition();

    Long getClassifierId();

    Integer getClassifierCode();

    String getClassifierName();

    String getSensorClassName();

    ProductType getProductType();

    Integer getSerialNumber();

    ProductionItemStatus getStatus();

    Integer getCustomerOrderItemPosition();

    String getNote();

    default String getProductTypeDisplayName() {
        ProductType type = getProductType();

        return type == null ? "—" : type.getDisplayName();
    }

    /* Серия в пять знаков: 22 → «00022». */
    default String getFormattedSerial() {
        Integer serial = getSerialNumber();

        return serial == null
                ? "—"
                : String.format("%05d", serial);
    }

    /* Полный номер изделия: 1212 + 00022 → «121200022». */
    default String getFullNumber() {
        Integer code = getClassifierCode();

        if (code == null) {
            return "—";
        }

        Integer serial = getSerialNumber();

        return serial == null
                ? String.valueOf(code)
                : code + String.format("%05d", serial);
    }

    /*
     * Полное наименование изделия без номера: «ДУУ2М-...». Для датчика
     * имя берётся из справочника датчиков (sensorClassName) — тот же
     * разбор, что в getProductLabel(), иначе одна позиция называлась бы
     * по-разному в разных колонках. Выводится отдельной колонкой
     * «Наименование» сразу после номера изделия.
     */
    default String getDisplayName() {
        String name = getSensorClassName() != null
                && !getSensorClassName().isBlank()
                ? getSensorClassName()
                : getClassifierName();

        return name == null || name.isBlank()
                ? "—"
                : name;
    }

    /* «121200022 — Название изделия»: для датчика имя из справочника. */
    default String getProductLabel() {
        String name = getSensorClassName() != null
                && !getSensorClassName().isBlank()
                ? getSensorClassName()
                : getClassifierName();

        return name == null || name.isBlank()
                ? getFullNumber()
                : getFullNumber() + " — " + name;
    }

    default String getStatusDisplayName() {
        ProductionItemStatus status = getStatus();

        return status == null ? "—" : status.getDisplayName();
    }

    default String getStatusBadgeClass() {
        ProductionItemStatus status = getStatus();

        return status == null ? "draft" : status.getBadgeClass();
    }
}
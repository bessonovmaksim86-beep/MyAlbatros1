package ru.company.production.repository;

import ru.company.production.entity.ProductType;

import java.time.LocalDateTime;

/**
 * Внесённая в заказ ячейка вместе с реквизитами её представления.
 *
 * Отличие от {@link AttachedCellView}: здесь есть тип изделия и
 * отметка готовности, потому что сводка поиска обязана назвать
 * разновидность позиции — «Ячейка» либо «Подсборка». Без
 * {@code cellReadyAt} граница не выводима (V75: принятая складом
 * позиция остаётся подсборкой, пока кладовщик не подтвердит
 * готовность), и сводка показала бы подсборку ячейкой.
 *
 * Порядок строк задаёт сопоставление ячейки экземпляру плана: тот же,
 * что у {@code SerialProductionOrderRepository#findAttachedCellsByOrderIds}
 * — заказ, момент внесения, затем номер.
 */
public interface ZnpCellPassageView {

    Long getSerialId();

    Integer getClassifierCode();

    Integer getSerial();

    String getClassifierName();

    String getSensorName();

    ProductType getProductType();

    LocalDateTime getCellReadyAt();

    /** Полный номер изделия: 5102 + 6 → «510200006». */
    default String getFullNumber() {

        Integer code = getClassifierCode();
        Integer number = getSerial();

        if (code == null || number == null) {
            return null;
        }

        return code + String.format("%05d", number);
    }

    /** Наименование изделия: справочник датчиков либо классификатор. */
    default String getDisplayName() {

        String sensor = pick(getSensorName());

        return sensor != null ? sensor : pick(getClassifierName());
    }

    private static String pick(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

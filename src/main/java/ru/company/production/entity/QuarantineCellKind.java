package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Разновидность изделия в изоляторе брака.
 *
 * Различие берётся не из типа классификатора — тот для обоих случаев
 * остаётся «Ячейка», — а из стадии маршрута, описанной в
 * {@link WarehouseNaming}: позиция прихода является подсборкой, пока
 * её не передали на склад полуфабрикатов, и получает статус ячейки
 * после приёмки складом.
 *
 * Разделение нужно руководителю службы качества: ремонт подсборки и
 * ремонт ячейки оформляют разными заказами (ЗНП), поэтому перечень
 * изолятора фильтруют именно по этой границе.
 */
@Getter
@RequiredArgsConstructor
public enum QuarantineCellKind {

    SUBASSEMBLY("Подсборка"),

    CELL("Ячейка");

    private final String displayName;

    /**
     * Разновидность по факту приёмки партии складом.
     *
     * @param accepted партию принял склад полуфабрикатов
     */
    public static QuarantineCellKind ofAccepted(boolean accepted) {
        return accepted ? CELL : SUBASSEMBLY;
    }
}

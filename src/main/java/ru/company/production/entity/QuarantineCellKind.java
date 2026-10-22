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
     * @deprecated V75: приёмка склада означает «позиция лежит на
     *             складе», а не «позиция — ячейка». Границу CELL /
     *             SUBASSEMBLY задаёт отметка готовности
     *             ({@code cell_ready_at}), поэтому новым коду нужен
     *             {@link #ofCellReady(boolean)}.
     */
    @Deprecated
    public static QuarantineCellKind ofAccepted(boolean accepted) {
        return accepted ? CELL : SUBASSEMBLY;
    }

    /**
     * Разновидность по отметке «готовая ячейка» (V75).
     *
     * Позиция приходной операции остаётся подсборкой, пока кладовщик
     * не подтвердит её готовность: приёмка после БВК кладёт её на полку,
     * но в комплектование, изолятор и отчёты она выходит ячейкой только
     * с отметкой {@code cell_ready_at}.
     *
     * @param cellReady позицию отметили готовой ячейкой
     */
    public static QuarantineCellKind ofCellReady(boolean cellReady) {
        return cellReady ? CELL : SUBASSEMBLY;
    }
}

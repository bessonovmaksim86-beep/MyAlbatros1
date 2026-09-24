package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид заказа на ремонт (ЗНП).
 *
 * Бизнес различает заказы не только по стадии, но и по назначению:
 * ремонт ячейки, ремонт подсборки и выдача комплектации идут разными
 * маршрутами и по разным изделиям, поэтому вид фиксируется в заказе
 * отдельно от статуса — «в работе» одинаково и у ремонта, и у выдачи.
 */
@Getter
@RequiredArgsConstructor
public enum RepairOrderType {

    CELL_REPAIR("ЗНП на ремонт ячеек"),

    SUBASSEMBLY_REPAIR("ЗНП на ремонт подсборок"),

    /**
     * Вид ранее оформленных заказов.
     *
     * Выдача комплектации стала отметкой к заказу на ремонт
     * (RepairOrder.kitsIssue), а не самостоятельным видом: комплектацию
     * выдают вместе с ремонтом ячейки или подсборки. Константу сохраняют
     * потому, что {@code @Enumerated(EnumType.STRING)} читает по имени —
     * удалить её означало бы не суметь прочитать заказы, заведённые
     * раньше. Новые заказы этого вида не создают.
     */
    @Deprecated
    KITS_ISSUE("ЗНП на выдачу комплектации");

    private final String displayName;

    /**
     * Вид по составу отмеченных ячеек.
     *
     * Нужен как подсказка форме: руководитель отмечает ячейки в
     * перечне изолятора, и однородный состав предопределяет вид заказа.
     * Смешанный или пустой состав вида не подсказывает — тогда вид
     * выбирают вручную.
     */
    public static RepairOrderType suggestFrom(java.util.Collection<QuarantineCellKind> kinds) {

        if (kinds == null || kinds.isEmpty()) {
            return null;
        }

        if (kinds.stream().allMatch(kind -> kind == QuarantineCellKind.CELL)) {
            return CELL_REPAIR;
        }

        if (kinds.stream().allMatch(kind -> kind == QuarantineCellKind.SUBASSEMBLY)) {
            return SUBASSEMBLY_REPAIR;
        }

        return null;
    }
}

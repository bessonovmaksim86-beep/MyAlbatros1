package ru.company.production.dto;

import ru.company.production.entity.ProductionOrder;
import ru.company.production.service.QuarantineCellView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Карточка комплектования группового ЗНП на приборы: заказ, его виды
 * ячеек, внесённый состав и изолятор брака.
 *
 * Кладовщик видит реквизиты заказа, по каждому виду ячейки — сколько
 * требуется и сколько уже внесено, и вносит номера ячеек с полки.
 * Когда все виды укомплектованы — форма скрывается, заказ считается
 * укомплектованным и сдвигается по техпроцессу.
 *
 * Два перечня держатся раздельно по каждому виду (в {@link #kinds()}):
 * оформленная в ЗНП ячейка со склада снята, и показывать её в
 * доступных значило бы предлагать провести её повторно.
 *
 * @param order          заказ-комплектующий (групповой на приборы)
 * @param kinds          виды ячеек заказа с их укомплектованностью
 * @param attached       весь внесённый состав заказа (для справки)
 * @param quarantine     ячейки изолятора брака по видам заказа
 * @param operationName  наименование операции комплектования
 * @param changeNumber   номер изменения изделия — для подписи
 */
public record InstrumentKittingView(
        ProductionOrder order,
        List<InstrumentCellKindRow> kinds,
        List<SubassemblyKittingCellRow> attached,
        List<QuarantineCellView> quarantine,
        String operationName,
        String changeNumber,
        String formattedDueDate
) {

    /** Изделие заказа с номером изменения: «5004 — ПВВ1 изм.3». */
    public String getProductLabel() {
        return order == null
                ? "—"
                : order.getClassifierLabelWithChange(changeNumber);
    }

    /** Приборов в заказе (количество заказа) — множитель видов. */
    public long instrumentCount() {
        BigDecimal quantity = order == null ? null : order.getQuantity();
        return quantity == null
                ? 0L
                : quantity.setScale(0, RoundingMode.CEILING).longValue();
    }

    /** Ячеек всех видов уже внесено в заказ. */
    public long takenCount() {
        return kinds == null
                ? 0L
                : kinds.stream().mapToLong(InstrumentCellKindRow::attachedCount).sum();
    }

    /** Ячеек всех видов требуется на заказ. */
    public long requiredCount() {
        return kinds == null
                ? 0L
                : kinds.stream().mapToLong(InstrumentCellKindRow::requiredTotal).sum();
    }

    /**
     * Вносить нечего: все виды укомплектованы.
     *
     * Требуемое обязано быть больше нуля: заказ без видов (в маршруте
     * не заданы ячейки) не считается укомплектованным — иначе экран
     * скрыл бы форму там, где технологи ещё не описали состав.
     */
    public boolean nothingToAdd() {
        long required = requiredCount();
        return required > 0 && takenCount() >= required;
    }

    /** Есть ли у заказа заданные виды ячеек. */
    public boolean hasKinds() {
        return kinds != null && !kinds.isEmpty();
    }
}

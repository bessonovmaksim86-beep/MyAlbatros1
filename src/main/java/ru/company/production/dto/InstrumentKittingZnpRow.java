package ru.company.production.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Строка списка «Комплектация групп ЗНП на приборы».
 *
 * Групповой ЗНП на приборы набирается несколькими видами ячеек,
 * которые технолог задал в контролируемых параметрах операции
 * комплектования маршрута. Строка списка сводит заказ и его виды
 * ячеек: кладовщик видит, по каким видам заказ ещё не укомплектован,
 * и открывает карточку заказа.
 *
 * Техпроцесс показан вместо родителя: групповой ЗНП заводят без
 * выпуска (V38), и колонка «Родитель» была бы прочерком, тогда как
 * маршрут заказа кладовщику полезен — по нему сверяют операцию.
 *
 * Срок ({@code formattedDueDate}) — дата окончания плана заказа, а не
 * срок из его карточки: склад ориентируется на график цеха.
 */
public record InstrumentKittingZnpRow(
        Long id,
        String number,
        String znpKindDisplayName,
        String classifierLabel,
        String techProcessCode,
        String operationName,
        String formattedOrderDate,
        String formattedDueDate,
        String statusDisplayName,
        String statusBadgeClass,
        String note,
        BigDecimal requiredQuantity,
        /* Приборов в заказе (количество заказа) — множитель видов. */
        long instrumentCount,
        /* Виды ячеек заказа с их укомплектованностью. */
        List<InstrumentCellKindRow> kinds
) {

    /** Сколько ячеек всех видов уже внесено в заказ. */
    public long doneCount() {
        if (kinds == null) {
            return 0L;
        }
        return kinds.stream().mapToLong(InstrumentCellKindRow::attachedCount).sum();
    }

    /** Сколько ячеек всех видов требуется на заказ. */
    public long requiredCount() {
        if (kinds == null) {
            return 0L;
        }
        return kinds.stream().mapToLong(InstrumentCellKindRow::requiredTotal).sum();
    }

    /** Подпись готовности: «Внесено X из Y яч.». */
    public String readinessLabel() {
        return "Внесено " + doneCount() + " из " + requiredCount() + " яч.";
    }

    /** Заказ укомплектован: внесено требуемое по всем видам. */
    public boolean completed() {
        long required = requiredCount();
        return required > 0 && doneCount() >= required;
    }

    /**
     * Заказ укомплектован не полностью.
     *
     * Обратная сторона {@link #completed()}: по этому признаку считают
     * счётчик меню, и цифра обязана равняться числу строк, где внесено
     * меньше требуемого.
     *
     * Заказ без видов в счётчик не входит, но и укомплектованным он не
     * считается: см. {@link #awaitingKinds()}. Иначе склад увидел бы
     * «укомплектован» у заказа, в который не внесено ни одной ячейки,
     * — требуемое количество просто не задано.
     */
    public boolean incomplete() {
        long required = requiredCount();
        return required > 0 && doneCount() < required;
    }

    /**
     * Видов ячеек не задано: по заказу вести нечего.
     *
     * Отдельный признак, а не {@code incomplete()}: у незавершённого
     * заказа видно, сколько внесено и сколько требуется, а здесь
     * требуемого просто нет — и показывать «укомплектован» при нулевом
     * составе ошибочно.
     */
    public boolean awaitingKinds() {
        return requiredCount() <= 0;
    }

    /**
     * Подпись колонки «Заказано».
     *
     * Три состояния, а не два: заказ без заданных видов (в маршруте не
     * расписаны ячейки) раньше печатался как «укомплектован», потому что
     * требуемое количество равно нулю и сравнение «внесено &lt;
     * требуемого» ложно. Это вводило кладовщика в заблуждение: в заказ
     * не вошла ни одна ячейка, а экран обещал, что вести нечего.
     */
    public String kittingLabel() {
        if (awaitingKinds()) {
            return "виды ячеек не заданы";
        }

        return completed() ? "укомплектован" : readinessLabel();
    }

    /**
     * CSS-модификатор метки колонки «Заказано».
     *
     * {@code draft} — серый: состояние не готовность и не работа, а
     * отсутствие данных от технолога.
     */
    public String kittingBadgeClass() {
        if (awaitingKinds()) {
            return "draft";
        }

        return completed() ? "done" : "work";
    }
}

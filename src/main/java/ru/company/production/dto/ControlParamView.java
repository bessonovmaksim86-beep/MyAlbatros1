package ru.company.production.dto;

import ru.company.production.entity.ControlParam;
import ru.company.production.entity.ControlParamValue;

import java.util.List;

/**
 * Контролируемый параметр для формы проведения.
 *
 * Технолог задаёт в маршруте вид параметра, его режим и обязательность.
 * Форма показывает:
 *   LIST — выпадающий список вариантов справочника,
 *   TEXT — текстовое поле с подсказкой из условия ТП,
 *   CELL — фактический серийный номер / ячейку экземпляра и
 *          классификатор, который требует техпроцесс.
 *
 * Режим CELL важнее типа: параметр про ячейку выбирает значение из
 * состава изделия, даже если в справочнике его завели как «поле ввода».
 */
public record ControlParamView(

        /** Идентификатор строки КП в маршруте. */
        Long id,

        /** Наименование вида параметра. */
        String name,

        /** Режим заполнения. */
        Mode mode,

        /** Обязателен ли параметр. */
        boolean mandatory,

        /** Варианты для LIST. */
        List<Option> options,

        /** Подсказка технолога (TEXT). */
        String hint,

        /** Классификатор, требуемый технологом (CELL). */
        Long requiredClassifierId,

        /** Подпись требуемого классификатора (CELL). */
        String requiredClassifierLabel,

        /** Фактический номер ячейки / серийника экземпляра (CELL). */
        String actualCellNumber,

        /** Классификатор фактической ячейки (CELL). */
        Long actualClassifierId,

        /** Подпись фактического классификатора (CELL). */
        String actualClassifierLabel,

        /**
         * Номер ячейки, привязанной к прибору этим параметром
         * (CELL у заказа на приборы); пусто — привязки нет.
         */
        String boundCellNumber,

        /** Подпись классификатора привязанной ячейки. */
        String boundCellClassifierLabel
) {

    public enum Mode { LIST, TEXT, CELL }

    public record Option(Long id, String value) {}

    /**
     * Совпадает ли классификатор фактической ячейки с требуемым.
     *
     * Для прибора сверяется привязанная ячейка: фактическим
     * изделием экземпляра здесь является прибор, а не ячейка, и
     * классификатор подставляется тот, что у внесённого номера
     * (сервис заполняет actualClassifierId привязкой), поэтому
     * сравнение остаётся одним и тем же для обоих режимов.
     */
    public boolean isCellClassifierMatch() {
        if (mode != Mode.CELL) {
            return true;
        }

        if (requiredClassifierId == null) {
            return true;
        }

        return requiredClassifierId.equals(actualClassifierId);
    }

    /** Есть ли у экземпляра фактическая ячейка или серийник. */
    public boolean hasActualCell() {
        return actualCellNumber != null && !actualCellNumber.isBlank();
    }

    /**
     * Привязана ли к экземпляру ячейка этого параметра.
     *
     * Для заказа на приборы именно эта отметка решает, можно ли
     * проводить операцию: у прибора несколько видов ячеек, и пока
     * один из них не внесён, изделие собрано не полностью.
     */
    public boolean isCellBound() {
        return boundCellNumber != null && !boundCellNumber.isBlank();
    }

    /*
     * Проверки режима читаются как методы, а не сравнением строки:
     * перечисление в шаблоне через T(...) связывает разметку с полным
     * именем класса, и переименование пакета молча выключило бы
     * половину полей формы.
     */
    public boolean isListMode() {
        return mode == Mode.LIST;
    }

    public boolean isTextMode() {
        return mode == Mode.TEXT;
    }

    public boolean isCellMode() {
        return mode == Mode.CELL;
    }

}

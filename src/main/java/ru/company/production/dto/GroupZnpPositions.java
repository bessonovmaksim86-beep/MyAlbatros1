package ru.company.production.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Где сейчас позиции группового ЗНП — сводка списка (колонка «Где позиции»).
 *
 * Заказ в работе держит группу ячеек, и диспетчеру важно видеть не
 * маршрут целиком, а работу, на которой эти ячейки стоят: по ней
 * понятно, к кому идти.
 *
 * Подпись читается как «Цех подготовки производства (Лакирование
 * подсборок — 4 шт.)»: сначала подразделение — оно отвечает за заказ и
 * его ищут глазами, затем работа и количество позиций. Подразделения у
 * строки плана может и не быть (план построен до назначения адреса),
 * тогда остаётся только работа — выдумывать адрес колонка не вправе.
 *
 * @param unitName      подразделение, ведущее работу; пустое, если план
 *                      его не задал
 * @param operationName наименование работы, на которой стоит больше всего
 *                      ранних позиций; пустая, если незакрытых работ нет
 * @param count         сколько позиций стоит на этой работе
 * @param byWork        позиции по каждой незакрытой работе в порядке
 *                      маршрута — для подсказки, когда заказ разбросан
 *                      по нескольким работам
 */
public record GroupZnpPositions(
        String unitName,
        String operationName,
        long count,
        Map<Work, Long> byWork
) {

    /** Незакрытых позиций нет — колонка показывает прочерк. */
    public static final GroupZnpPositions EMPTY =
            new GroupZnpPositions(null, null, 0L, Map.of());

    /**
     * Работа вместе со своим подразделением.
     *
     * Одна и та же работа у разных подразделений — разные очереди:
     * смешав их в одну строку, колонка показала бы чужой цех там, где
     * работу ждут в другом.
     */
    public record Work(String unitName, String operationName) {

        /** Подпись работы: «Цех (Лакирование подсборок)». */
        public String label() {

            String unit = blankToNull(unitName);
            String work = blankToNull(operationName);

            if (work == null) {
                return unit == null ? "—" : unit;
            }

            return unit == null ? work : unit + " (" + work + ")";
        }
    }

    public boolean isEmpty() {
        return operationName == null || operationName.isBlank();
    }

    /**
     * Подпись колонки: «Цех (Лакирование подсборок — 4 шт.)».
     *
     * Количество — сколько ячеек стоит на работе. Нулевое означает
     * «работа заявлена, ячеек ещё нет»: заказ только взят в работу, и
     * колонка показывает первую работу маршрута без счёта — считать
     * нечего, а адрес заказа диспетчеру нужен уже сейчас.
     */
    public String label() {

        if (isEmpty()) {
            return "—";
        }

        String unit = blankToNull(unitName);
        String work = operationName.trim();
        String counted = count > 0
                ? work + " — " + count + " шт."
                : work;

        /*
         * Количество живёт внутри скобок вместе с работой: подразделение
         * — адрес, а «сколько» относится к работе. Наружу вынесенное
         * число читалось бы как объём всего заказа.
         */
        return unit == null ? counted : unit + " (" + counted + ")";
    }

    /**
     * Подсказка: все работы с позициями, если их больше одной.
     *
     * Одна работа — подсказки нет: то же самое читается в клетке, а
     * дублирование только мешает.
     */
    public String title() {

        if (byWork == null || byWork.size() < 2) {
            return null;
        }

        List<String> parts = new ArrayList<>(byWork.size());

        byWork.forEach((work, value) ->
                parts.add(work.label() + " — " + value));

        return "Позиции ждут работ: " + String.join("; ", parts);
    }

    static String blankToNull(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}

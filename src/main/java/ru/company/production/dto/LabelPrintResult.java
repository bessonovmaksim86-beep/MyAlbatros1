package ru.company.production.dto;

/**
 * Итог передачи партии номеров в BarTender.
 *
 * Результат нужен не как «да/нет»: оператор обязан понять, ушли ли
 * этикетки на принтер или печать вообще не состоялась, — иначе он
 * отсканирует то, чего напечатать не удалось.
 *
 * @param printed партия передана в BarTender, задание печати запущено
 * @param labels  сколько этикеток поставит принтер (номера × копии)
 * @param message готовая надпись для оператора
 */
public record LabelPrintResult(
        boolean printed,
        int labels,
        String message
) {

    public static LabelPrintResult printed(int labels, String message) {
        return new LabelPrintResult(true, labels, message);
    }

    public static LabelPrintResult failed(String message) {
        return new LabelPrintResult(false, 0, message);
    }
}

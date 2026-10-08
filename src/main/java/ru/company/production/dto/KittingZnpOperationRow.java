package ru.company.production.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Одна операция комплектования внутри раскрытой строки изделия.
 *
 * Раздел «Комплектование ЗНП» показывает работу разрезом «изделие →
 * его операции»: главная строка таблицы — серийный номер, а раскрытие
 * перечисляет операции этого изделия, под которые заведён заказ-
 * комплектующий (ЗНП) в статусе «в работе». Карточка операции держит
 * ровно те подписи, что просит кладовщик: наименование операции, номер
 * привязанного ЗНП, дата плана и возможность провести.
 *
 * Проводится конкретная строка графика, поэтому карточка несёт
 * {@code rowId} — ключ существующего {@code POST /warehouse/kitting/
 * complete}; отдельного write-пути раздел не заводит.
 *
 * @param rowId          строка графика — ключ проведения
 * @param operationName  наименование операции («Комплектование»)
 * @param znpNumber      номер привязанного ЗНП (заказ в работе)
 * @param plannedDate    дата плана этой операции
 * @param completed      операция уже проведена
 * @param completedLabel дата проведения для подписи
 * @param blocked        есть незакрытая предшествующая по маршруту
 * @param blockerLabel   наименование блокирующей операции
 * @param canComplete    провести можно сейчас (не закрыта, не ждёт)
 */
public record KittingZnpOperationRow(
        Long rowId,
        String operationName,
        String znpNumber,
        LocalDate plannedDate,
        boolean completed,
        String completedLabel,
        boolean blocked,
        String blockerLabel,
        boolean canComplete
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Наименование операции — подпись колонки. */
    public String title() {
        return operationName == null || operationName.isBlank()
                ? "операция"
                : operationName.trim();
    }

    /** Номер ЗНП отдельной подписью; пустое значение — прочерк. */
    public String znpLabel() {
        return znpNumber == null || znpNumber.isBlank()
                ? "—"
                : znpNumber.trim();
    }

    /** Дата плана в привычном формате — диалект java8time не подключён. */
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /**
     * Подпись причины блокировки: «ждёт: Сборка узла». Проведённая
     * операция блокироваться не может, поэтому для неё — прочерк.
     */
    public String blockedLabel() {
        if (!blocked) {
            return "—";
        }

        return blockerLabel == null || blockerLabel.isBlank()
                ? "ждёт предшественника"
                : "ждёт: " + blockerLabel.trim();
    }
}

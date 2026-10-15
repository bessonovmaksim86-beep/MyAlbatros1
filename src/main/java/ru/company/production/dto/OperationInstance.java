package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Экземпляр операции — одна строка графика (V43): одна операция
 * одного экземпляра одной позиции заказа.
 *
 * В интерфейсах операции показываются группами («операция + дата»),
 * но провести работу можно поштучно: позиция на десять изделий
 * закрывается десятью фактами. Поэтому у группы появляется список
 * экземпляров, и каждый несёт идентификатор строки, по которому
 * идёт проведение.
 *
 * Серийного номера у строки нет: заказ планируется до выпуска (V43),
 * поэтому экземпляр подписывается парой «позиция · номер внутри
 * позиции» — это идентификатор изделия на плановой стадии.
 *
 * @param rowId         строка графика — ключ проведения
 * @param itemPosition  позиция заказа
 * @param instanceIndex номер экземпляра внутри позиции, 0-based
 * @param orderNumber   заказ покупателя, которому принадлежит строка
 * @param hours         трудоёмкость экземпляра, человеко-часы
 * @param completed     экземпляр проведён
 * @param completedLabel дата проведения для таблицы
 * @param canComplete   провести может исполнитель, на которого
 *                      экземпляр закреплен и пока он не закрыт
 * @param serialNumber  серийный номер выпуска, до выпуска — пусто
 * @param itemFullName  полное наименование изделия из позиции заказа
 * @param blocked       есть незакрытая предшествующая операция по
 *                      маршруту — проведение запрещено
 * @param blockerLabel  название блокирующей операции для подписи
 * @param cellNumber    номер ячейки, внесённой складом в групповой ЗНП
 *                      (V20); для строк выпуска — пусто
 */
public record OperationInstance(
        Long rowId,
        int itemPosition,
        int instanceIndex,
        String orderNumber,
        BigDecimal hours,
        boolean completed,
        String completedLabel,
        boolean canComplete,
        String serialNumber,
        String itemFullName,
        boolean blocked,
        String blockerLabel,
        String cellNumber
) {

    /**
     * Подпись причины блокировки: «ждёт: ФРЕЗЕРНАЯ». Проведённый
     * экземпляр блокироваться не может, поэтому для него — прочерк,
     * иначе закрытая работа выглядела бы незавершённой.
     */
    public String blockedLabel() {
        if (!blocked) {
            return "—";
        }

        return blockerLabel == null || blockerLabel.isBlank()
                ? "ждёт предшественника"
                : "ждёт: " + blockerLabel.trim();
    }

    /** Идентификатор экземпляра: «поз. 3 · экз. 2». */
    public String instanceLabel() {
        return "поз. " + itemPosition + " · экз. " + (instanceIndex + 1);
    }

    /** Трудоёмкость экземпляра без хвостовых нулей. */
    public String hoursLabel() {
        if (hours == null) {
            return "—";
        }

        return hours.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Изделие одной подписью: серийный номер выпуска, а до выпуска —
     * плановая подпись «поз. · экз.».
     */
    public String productLabel() {
        return serialNumber == null || serialNumber.isBlank()
                ? instanceLabel()
                : serialNumber.trim();
    }

    /**
     * Серийный номер отдельной подписью: до выпуска — прочерк, а не
     * плановая подпись, иначе колонка повторяла бы соседнюю.
     */
    public String serialLabel() {
        return serialNumber == null || serialNumber.isBlank()
                ? "—"
                : serialNumber.trim();
    }

    /**
     * Номер ячейки из состава группового ЗНП; прочерк, если ячейки нет.
     *
     * В групповом режиме «Моих операций» это основная подпись экземпляра:
     * серийного номера выпуска у группового заказа не бывает вовсе, и
     * исполнитель обязан видеть именно тот номер, который склад внёс в
     * заказ. Прочерк честнее плановой подписи «поз. · экз.»: работы
     * экземпляра существуют до того, как кладовщик внёс в заказ номер.
     */
    public String cellLabel() {
        return cellNumber == null || cellNumber.isBlank()
                ? "—"
                : cellNumber.trim();
    }

    /** Полное наименование из позиции заказа («как в КД»). */
    public String nameLabel() {
        return itemFullName == null || itemFullName.isBlank()
                ? "—"
                : itemFullName.trim();
    }
}

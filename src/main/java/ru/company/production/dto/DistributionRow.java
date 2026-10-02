package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Плоская строка распределения — один экземпляр одной операции.
 *
 * Интерфейс распределения намеренно не группирует работу по паре
 * «операция + дата»: мастер раскладывает по людям конкретные изделия,
 * и одинаковая операция в разных строках может уйти разным
 * сотрудникам. Поэтому единица таблицы — строка графика, а не группа.
 *
 * Одно и то же представление служит обеим таблицам раздела:
 * в очереди распределения заполнен {@code executors} (список способных
 * для выпадающего выбора), а {@code executorId}/{@code executorLabel}
 * пустуют; в уже распределённых, наоборот, заполнен исполнитель, а
 * {@code executors} пуст.
 *
 * Серийного номера у самой строки графика нет: заказ планируется до
 * выпуска (V43), поэтому экземпляр подписывается парой «позиция ·
 * номер внутри позиции» ({@code itemLabel}). Когда выпуск этого
 * экземпляра уже создан, к строке подвязывается его серийный номер
 * ({@code serialNumber}) и полное наименование изделия
 * ({@code itemFullName}) — их мастер и обязан видеть, раскладывая
 * работу по людям. Пока выпуск не создан, оба поля null, и таблица
 * откатывается на плановую подпись {@code itemLabel}.
 *
 * @param rowId         строка графика — ключ назначения/снятия
 * @param itemLabel     номер изделия на плановой стадии: «поз. 3 · экз. 1»
 * @param orderNumber   номер заказа покупателя (Номер ЗП)
 * @param operationCode шифр операции — служебный, в таблице не показывается
 * @param operationName наименование операции
 * @param plannedDate   дата плана
 * @param hours         трудоёмкость экземпляра, человеко-часы
 * @param executors     способные сотрудники (пусто для назначенных)
 * @param executorId    закреплённый исполнитель (пусто в очереди)
 * @param executorLabel подпись закреплённого исполнителя
 * @param kitReceipt    экземпляр относится к получению комплектации
 * @param serialNumber  серийный номер выпускаемого экземпляра
 *                      (полное число вида «121200022»), null до выпуска
 * @param itemFullName  полное наименование изделия позиции заказа
 *                      («как в КД»), null если не удалось определить
 */
public record DistributionRow(
        Long rowId,
        String itemLabel,
        String orderNumber,
        String operationCode,
        String operationName,
        LocalDate plannedDate,
        BigDecimal hours,
        List<AssignableExecutor> executors,
        Long executorId,
        String executorLabel,
        boolean kitReceipt,
        String serialNumber,
        String itemFullName
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Дата плана в привычном формате. */
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /*
     * Операция одной подписью: только наименование.
     *
     * Шифр вида «OP-40646AF4243B4597A97» в таблице не показывают: он
     * ничего не говорит мастеру, а в узкой колонке вытеснял само
     * название работы. Код остаётся в DTO служебным — по нему строятся
     * подписи и фильтры внутри сервиса.
     */
    public String operationLabel() {
        String name = operationName == null ? "" : operationName.trim();

        return name.isEmpty() ? "Операция не указана" : name;
    }

    /** Номер ЗП без прочерка для пустого заказа. */
    public String orderLabel() {
        return orderNumber == null || orderNumber.isBlank()
                ? "—"
                : orderNumber.trim();
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

    /** Экземпляр уже закреплён за исполнителем. */
    public boolean assigned() {
        return executorId != null;
    }

    /** Есть ли кого выбирать в выпадающем списке. */
    public boolean hasExecutors() {
        return executors != null && !executors.isEmpty();
    }

    /*
     * Подпись колонки «Номер изделия».
     *
     * Как только выпуск экземпляра создан и серийный номер известен,
     * показывается он — именно этот номер мастер сверяет с изделием в
     * руках. До выпуска серийника нет, и колонка откатывается на
     * плановую подпись «поз. · экз.».
     */
    public String productNumberLabel() {
        if (serialNumber != null && !serialNumber.isBlank()) {
            return serialNumber.trim();
        }

        return itemLabel == null || itemLabel.isBlank()
                ? "—"
                : itemLabel.trim();
    }

    /** Подпись колонки «Наименование»: полное обозначение или прочерк. */
    public String itemFullNameLabel() {
        return itemFullName == null || itemFullName.isBlank()
                ? "—"
                : itemFullName.trim();
    }
}

package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Строка заказа покупателя, извлечённая из выгрузки отчёта
 * «Анализ заказов покупателей» (1С: УПП 1.3).
 *
 * Единица обмена: одна строка = один заказ покупателя, то есть заказ
 * на производство верхнего уровня (ЗП) в терминах нашей системы.
 *
 * Позиция строки листа сохраняется: выгрузку формируют руками, и без
 * номера строки администратор не нашёл бы, откуда взялась сомнительная
 * сумма.
 *
 * @param number            номер заказа покупателя в 1С («00000000450»)
 * @param orderDate         дата документа; null, если 1С отдала
 *                          нечитаемое значение
 * @param customer          контрагент; null, если уровень иерархии
 *                          не восстановлен (текст из буфера отступов
 *                          не несёт — см. OneCAnalysisParser)
 * @param parentLabel       подпись строки-родителя текстом (договор или
 *                          вид операции); справочная, на расчёты не влияет
 * @param plannedShipment   отгружено по плану, в единицах хранения
 * @param remainingShipment остаток отгрузки; 0 или пусто — заказ закрыт
 * @param lineNo            номер строки листа, для разбора ошибок
 */
public record OneCZpImportRow(
        String number,
        LocalDate orderDate,
        String customer,
        String parentLabel,
        BigDecimal plannedShipment,
        BigDecimal remainingShipment,
        int lineNo
) {

    /**
     * Закрыт ли заказ: отгрузка завершена.
     *
     * Признак задан владельцем базы: заказ считается закрытым, когда он
     * ОТГРУЖЕН, а не когда оплачен. Отрицательный остаток — перерасход
     * (отпущено больше плана) — тоже закрытое состояние: долга по
     * отгрузке в нём нет.
     */
    public boolean isClosed() {
        return remainingShipment == null
                || remainingShipment.signum() <= 0;
    }

    /** Подпись статуса для таблицы: читается без сверки с числом. */
    public String getStatusLabel() {
        return isClosed() ? "Отгружен" : "Не отгружен";
    }

    /** Клиент для таблицы: прочерк, если уровень не восстановлен. */
    public String getCustomerLabel() {
        return customer == null || customer.isBlank()
                ? "—"
                : customer;
    }

    /** Дата для таблицы в формате «дд.мм.гггг». */
    public String getFormattedOrderDate() {
        return orderDate == null
                ? "—"
                : orderDate.format(java.time.format.DateTimeFormatter
                        .ofPattern("dd.MM.yyyy"));
    }

    /** План отгрузки без хвостовых нулей: «3», а не «3.000». */
    public String getFormattedPlannedShipment() {
        return formatQuantity(plannedShipment);
    }

    /** Остаток отгрузки без хвостовых нулей; пусто — прочерк. */
    public String getFormattedRemainingShipment() {
        return formatQuantity(remainingShipment);
    }

    private static String formatQuantity(BigDecimal value) {
        if (value == null) {
            return "—";
        }

        return value.stripTrailingZeros().toPlainString();
    }
}

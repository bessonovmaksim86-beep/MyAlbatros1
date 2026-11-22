package ru.company.production.dto;

import java.time.LocalDate;

/**
 * Строка выгрузки ЗП, полученная из базы 1С.
 *
 * Отдельная запись, а не проекция {@code ZpExportView}: та описывает
 * выборку из собственной базы приложения, где сущности known и
 * форматирование делает Hibernate. Здесь данные приходят из внешней
 * системы, чью структуру настраивают SQL-запросом, и статус остаётся
 * произвольной строкой, которую ещё только предстоит разобрать.
 *
 * @param number              номер заказа/документа в 1С
 * @param orderDate           дата документа; null, если 1С отдала
 *                            нечитаемое значение
 * @param customerOrderNumber номер связанного заказа покупателя
 * @param customerName        наименование контрагента
 * @param statusRaw           статус текстом так, как его отдаёт 1С
 */
public record OneCZpRow(
        String number,
        LocalDate orderDate,
        String customerOrderNumber,
        String customerName,
        String statusRaw
) {

    private static final java.time.format.DateTimeFormatter RU_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Отнесён ли документ к закрытым.
     *
     * Признак вычисляется по тексту статуса, потому что в базах 1С
     * документы проводят и закрывают разными формулировками: «Проведён»,
     * «Реализован», «Закрыт», «Выполнен». Всё, что не распознано как
     * закрытое, считается оформленным — незакрытая часть обмена.
     */
    public boolean isClosed() {
        String value = statusRaw == null
                ? ""
                : statusRaw.trim().toLowerCase();

        return value.contains("провед")
                || value.contains("закры")
                || value.contains("выполн")
                || value.contains("реализ")
                || value.contains("отгруж")
                || value.contains("done")
                || value.contains("closed");
    }

    /** Дата для таблицы: формат «дд.мм.гггг», пустая — прочерк. */
    public String getFormattedOrderDate() {
        if (orderDate == null) {
            return "—";
        }

        return orderDate.format(RU_DATE);
    }

    /** Клиент для таблицы: наименование, иначе прочерк. */
    public String getCustomerLabel() {
        return customerName == null || customerName.isBlank()
                ? "—"
                : customerName;
    }

    /** Статус для таблицы: как отдали, либо прочерк. */
    public String getStatusLabel() {
        return statusRaw == null || statusRaw.isBlank()
                ? "—"
                : statusRaw;
    }
}

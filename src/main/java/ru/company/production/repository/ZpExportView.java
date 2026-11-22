package ru.company.production.repository;

import ru.company.production.entity.ProductionOrderStatus;

import java.time.LocalDate;

/**
 * Строка выгрузки ЗП для 1С: номер и клиент.
 *
 * Скалярная проекция — читает только поля, нужные для сверки выпуска
 * в 1С, без состава и серийников. По мере расширения обмена поля
 * добавляются, а запрос остаётся отдельным от штатных списков
 * диспетчера (см. {@link OneCRepository}).
 */
public interface ZpExportView {

    String getNumber();

    LocalDate getOrderDate();

    String getCustomerOrderNumber();

    String getCustomerName();

    ProductionOrderStatus getStatus();

    /*
     * Дата в формате «дд.мм.гггг»: шаблон не тянет java8time-диалект,
     * поэтому значение форматируется здесь — тот же приём, что у
     * {@link CustomerOrderListItemView#getFormattedOrderDate()}.
     */
    default String getFormattedOrderDate() {
        return CustomerOrderDateFormatter.format(getOrderDate());
    }

    default String getStatusDisplayName() {
        ProductionOrderStatus status = getStatus();

        return status == null ? "—" : status.getDisplayName();
    }

    /*
     * Клиент для выдачи: сначала наименование контрагента, если оно
     * есть; иначе номер заказа покупателя; иначе прочерк. 1С сверяет
     * выпуск по заказчику, и пустая клетка читалась бы как потерянные
     * данные.
     */
    default String getCustomerLabel() {
        String name = getCustomerName();

        if (name != null && !name.isBlank()) {
            return name;
        }

        String orderNumber = getCustomerOrderNumber();

        return orderNumber == null || orderNumber.isBlank()
                ? "—"
                : orderNumber;
    }
}

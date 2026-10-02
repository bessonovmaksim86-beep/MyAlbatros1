package ru.company.production.repository;

import java.time.LocalDate;

/**
 * Строка списка заказов покупателя.
 *
 * Заказ показывает сводку по всем его позициям:
 * сколько изделий (позиций) включено в заказ
 * и какое количество запланировано суммарно.
 */
public interface CustomerOrderListItemView {

    Long getId();

    String getNumber();

    LocalDate getOrderDate();

    LocalDate getDueDate();

    Long getCustomerId();

    String getCustomerName();

    ru.company.production.entity.CustomerOrderStatus getStatus();

    /*
     * Из приоритета берутся только отображаемые поля: строка списка
     * формируется запросом, а ленивая ссылка на сам справочник вне
     * сессии (open-in-view выключен) не читаема.
     */
    String getPriorityName();

    String getPriorityBadge();

    String getB24OrderUrl();

    String getB24ChecklistUrl();

    Boolean getActive();

    /* Количество изделий (позиций) в заказе. */
    Long getItemCount();

    /* Суммарное количество по всем позициям заказа. */
    java.math.BigDecimal getPlannedQuantity();

    /*
     * Даты отдаются в формате «дд.мм.гггг»,
     * чтобы в шаблонах не требовался дополнительный диалект.
     */
    default String getFormattedOrderDate() {
        return CustomerOrderDateFormatter.format(getOrderDate());
    }

    default String getFormattedDueDate() {
        return CustomerOrderDateFormatter.format(getDueDate());
    }

    /*
     * Бейджи статуса и приоритета: приоритет читается из справочника,
     * цвет задаётся его же полем badge_class — так правила и их оформление
     * живут рядом, а не разбросаны по шаблонам.
     */
    default String getStatusBadgeClass() {
        ru.company.production.entity.CustomerOrderStatus status = getStatus();

        return status == null
                ? "draft"
                : status.getBadgeClass();
    }

    /*
     * Признаки приостановки для строк списка (миграция V46): по ним
     * таблица решает, какую кнопку показать — «Приостановить» или
     * «Возобновить». Правило то же, что у CustomerOrder.isCanSuspend():
     * остановке подлежит только действующий незакрытый заказ. Держится
     * default-методом проекции, чтобы список не тянул лишнюю колонку и
     * не дублировал сравнение enum в шаблоне.
     */
    default boolean isSuspended() {
        return getStatus()
                == ru.company.production.entity.CustomerOrderStatus.SUSPENDED;
    }

    default boolean isCanSuspend() {
        ru.company.production.entity.CustomerOrderStatus status = getStatus();

        boolean isActive = getActive() == null || getActive();

        return isActive
                && status != null
                && status != ru.company.production.entity.CustomerOrderStatus.SUSPENDED
                && status != ru.company.production.entity.CustomerOrderStatus.DONE
                && status != ru.company.production.entity.CustomerOrderStatus.CANCELLED
                && status != ru.company.production.entity.CustomerOrderStatus.DELETED;
    }

    default String getPriorityDisplayName() {
        String name = getPriorityName();

        return name == null || name.isBlank()
                ? "Обычный"
                : name;
    }

    default String getPriorityBadgeClass() {
        String badge = getPriorityBadge();

        return badge == null || badge.isBlank()
                ? "priority-ordinary"
                : badge;
    }

    /*
     * Количество без лишней дроби: 10.000 → «10», 2.500 → «2.5».
     */
    default String getFormattedPlannedQuantity() {
        java.math.BigDecimal quantity = getPlannedQuantity();

        if (quantity == null) {
            return "0";
        }

        return quantity
                .stripTrailingZeros()
                .toPlainString();
    }
}

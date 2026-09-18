package ru.company.production.repository;

import ru.company.production.entity.ProductionOrderStatus;

import java.time.LocalDate;

/**
 * Строка списка заказов на производство.
 *
 * Заказ показывает сводку по выпуску: сколько изделий заявлено
 * (позиций-экземпляров) и сколько из них уже собрано.
 */
public interface ProductionOrderListItemView {

    Long getId();

    String getNumber();

    LocalDate getOrderDate();

    LocalDate getDueDate();

    String getCustomerOrderNumber();

    /*
     * Номер родительского выпуска: null у заказа верхнего уровня (ЗП)
     * и заполнен у заказа-комплектующего (ЗНП). По нему же определяется
     * уровень — отдельного флага в проекции нет, чтобы не плодить
     * дублирующие друг друга поля.
     */
    String getParentOrderNumber();

    String getOperationName();

    /* Количество выпускаемых изделий (позиций заказа). */
    Long getItemCount();

    /* Сколько позиций доведено до статуса «Выполнена». */
    Long getDoneItemCount();

    ProductionOrderStatus getStatus();

    default String getFormattedOrderDate() {
        return CustomerOrderDateFormatter.format(getOrderDate());
    }

    default String getFormattedDueDate() {
        return CustomerOrderDateFormatter.format(getDueDate());
    }

    default String getStatusDisplayName() {
        ProductionOrderStatus status = getStatus();

        return status == null ? "—" : status.getDisplayName();
    }

    default String getStatusBadgeClass() {
        ProductionOrderStatus status = getStatus();

        return status == null ? "draft" : status.getBadgeClass();
    }

    /*
     * Уровень заказа для колонки списка. По сущности уровень считает
     * ProductionOrder.isRoot(), но проекция сущность не отдаёт, поэтому
     * уровень выводится из наличия номера родителя — тот же признак,
     * что и в базе (parent_production_order_id).
     */
    default boolean isComponent() {
        return getParentOrderNumber() != null;
    }

    default String getLevelDisplayName() {
        return isComponent()
                ? "Комплектующий (ЗНП)"
                : "Выпуск (ЗП)";
    }

    /* Родительский выпуск или «—», если заказ верхнего уровня. */
    default String getParentDisplay() {
        String parent = getParentOrderNumber();

        return parent == null || parent.isBlank() ? "—" : parent;
    }

    /* Готово: «собрано из заявленного», например «3 из 10». */
    default String getDoneProgress() {
        long total = getItemCount() == null ? 0L : getItemCount();
        long done = getDoneItemCount() == null ? 0L : getDoneItemCount();

        return done + " из " + total;
    }
}

package ru.company.production.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import ru.company.production.entity.CustomerOrderItemStatus;
import ru.company.production.entity.MeasurementUnit;

/**
 * Строка состава заказа покупателя:
 * изделие из классификатора и количество по нему.
 */
public interface CustomerOrderItemView {

    Long getId();

    /*
     * Алиас в запросе — itemPosition:
     * «position» является функцией HQL, поэтому
     * прямое использование в качестве алиаса небезопасно.
     */
    Integer getItemPosition();

    Long getClassifierId();

    Integer getClassifierCode();

    String getClassifierName();

    String getSensorClassName();

    /*
     * Полное обозначение позиции заказа (CustomerOrderItem.fullName):
     * «как в КД», вводится диспетчером. Пусто — когда позиция
     * описана только классификатором.
     */
    String getFullName();

    BigDecimal getQuantity();

    BigDecimal getDoneQuantity();

    MeasurementUnit getUnit();

    LocalDate getDueDate();

    CustomerOrderItemStatus getStatus();

    String getNote();

    default Integer getPosition() {
        return getItemPosition();
    }

    default String getFormattedDueDate() {
        return CustomerOrderDateFormatter.format(getDueDate());
    }

    default String getFormattedQuantity() {
        return formatQuantity(getQuantity());
    }

    default String getFormattedDoneQuantity() {
        return formatQuantity(getDoneQuantity());
    }

    default String getFormattedRemainingQuantity() {
        BigDecimal planned = getQuantity();
        BigDecimal done = getDoneQuantity();

        if (planned == null) {
            planned = BigDecimal.ZERO;
        }

        if (done == null) {
            done = BigDecimal.ZERO;
        }

        return formatQuantity(planned.subtract(done));
    }

    /*
     * Полное наименование изделия для строки состава.
     *
     * Приоритет — fullName позиции («как в КД»): у датчика
     * классификатор даёт только род, а полное обозначение с
     * параметрами живёт именно в fullName. Когда fullName пуст,
     * подставляются род датчика или имя классификатора — как в
     * ProductionOrderSerialProjection.getDisplayName().
     */
    default String getDisplayName() {
        String full = pick(getFullName());
        if (full != null) {
            return full;
        }

        String sensor = pick(getSensorClassName());
        if (sensor != null) {
            return sensor;
        }

        String name = pick(getClassifierName());
        return name == null ? "—" : name;
    }

    private static String pick(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    default String getUnitName() {
        return getUnit() == null
                ? ""
                : getUnit().getDisplayName();
    }

    private static String formatQuantity(BigDecimal value) {
        if (value == null) {
            return "0";
        }

        return value
                .stripTrailingZeros()
                .toPlainString();
    }
}

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

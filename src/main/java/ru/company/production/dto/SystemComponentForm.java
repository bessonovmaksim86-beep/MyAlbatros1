package ru.company.production.dto;

import lombok.Getter;
import lombok.Setter;
import ru.company.production.entity.InclusionMode;
import ru.company.production.entity.MeasurementUnit;

import java.math.BigDecimal;

/*
 * Строка состава системы.
 *
 * Заполняется ровно одно из полей: sensorCatalogId (род датчика
 * из каталога) либо componentClassifierId (прибор типа DEVICE).
 */
@Getter
@Setter
public class SystemComponentForm {

    private Long sensorCatalogId;

    private Long componentClassifierId;

    private InclusionMode inclusionMode;

    private BigDecimal quantity;

    /* Максимальное количество в системе. Пусто — не ограничено. */
    private BigDecimal maxQuantity;

    private MeasurementUnit unit;
}

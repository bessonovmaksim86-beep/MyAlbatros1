package ru.company.production.dto;

import lombok.Getter;
import lombok.Setter;
import ru.company.production.entity.InclusionMode;
import ru.company.production.entity.MeasurementUnit;

import java.math.BigDecimal;

@Getter
@Setter
public class ClassifierInclusionForm {

    private Long targetId;

    private InclusionMode inclusionMode;

    private BigDecimal quantity;

    private MeasurementUnit unit;
}

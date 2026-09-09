package ru.company.production.dto;
import jakarta.validation.constraints.*;
import lombok.*;
import ru.company.production.entity.MeasurementUnit;
import java.math.BigDecimal;
@Getter @Setter @NoArgsConstructor
public class ClassifierInclusionForm {
 @NotNull(message="Выберите входящее изделие") private Long targetId;
 @NotNull(message="Укажите количество")
 @DecimalMin(value="0.001",message="Количество должно быть не меньше 0.001")
 private BigDecimal quantity=BigDecimal.ONE;
 @NotNull(message="Выберите единицу измерения")
 private MeasurementUnit unit=MeasurementUnit.PCS;
}

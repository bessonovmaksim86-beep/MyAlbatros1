package ru.company.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.company.production.entity.ProductType;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ClassifierForm {
    private Long id;

    @NotNull(message = "Укажите код")
    @Min(value = 1000, message = "Минимальный код: 1000")
    @Max(value = 9999, message = "Максимальный код: 9999")
    private Integer code;

    @NotNull(message = "Выберите тип изделия")
    private ProductType productType;

    @Size(max = 255, message = "Не более 255 символов")
    private String name;

    private Long sensorCatalogId;

    @Size(max = 2000, message = "Не более 2000 символов")
    private String note;

    @Valid
    private List<ClassifierInclusionForm> inclusions = new ArrayList<>();

    @Valid
    private List<SystemComponentForm> systemComponents = new ArrayList<>();

    private Long version;
}

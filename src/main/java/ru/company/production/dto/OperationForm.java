package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.company.production.entity.OrganizationUnitType;
import ru.company.production.entity.OperationType;

@Getter
@Setter
@NoArgsConstructor
public class OperationForm {

    @NotBlank(message = "Укажите название операции")
    @Size(
            max = 255,
            message = "Название операции не должно превышать 255 символов"
    )
    private String name;

    @NotNull(message = "Выберите тип операции")
    private OperationType type;

    @NotNull(message = "Выберите тип подразделения")
    private OrganizationUnitType targetType;

    @NotNull(message = "Выберите цех или отдел")
    @Positive(message = "Выберите корректное подразделение")
    private Long targetId;
}
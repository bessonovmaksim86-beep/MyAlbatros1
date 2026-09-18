package ru.company.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class TechProcessForm {

    private Long id;

    @NotBlank(message = "Укажите код техпроцесса")
    @Size(max = 50, message = "Не более 50 символов")
    private String code;

    @NotNull(message = "Выберите изделие классификатора")
    private Long productClassifierId;

    @Size(max = 2000, message = "Не более 2000 символов")
    private String note;

    @Valid
    private List<TechProcessOperationForm> operations =
            new ArrayList<>();

    private Long version;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class TechProcessOperationForm {

        @NotNull(message = "Выберите операцию")
        private Long operationId;

        @NotNull(message = "Укажите трудоёмкость")
        @DecimalMin(
                value = "0.001",
                message = "Трудоёмкость должна быть больше нуля"
        )
        @Digits(
                integer = 7,
                fraction = 3,
                message = "Трудоёмкость: не более 7 цифр и 3 после запятой"
        )
        private BigDecimal laborHours;

        @NotNull(message = "Укажите машиновремя")
        @DecimalMin(
                value = "0.001",
                message = "Машиновремя должно быть больше нуля"
        )
        @Digits(
                integer = 7,
                fraction = 3,
                message = "Машиновремя: не более 7 цифр и 3 после запятой"
        )
        private BigDecimal machineHours;

        @NotNull(message = "Укажите ограничение в день")
        @Min(value = 1, message = "Ограничение должно быть не меньше 1")
        private Integer dailyLimit;

        /*
         * Описание выполнения операции в данном маршруте.
         */
        @Size(max = 2000, message = "Не более 2000 символов")
        private String note;

        /*
         * Рабочие места, на которых выполняется операция.
         * Операция может быть распределена между несколькими РМ.
         */
        @NotEmpty(message = "Выберите хотя бы одно рабочее место")
        private List<Long> workPlaceIds = new ArrayList<>();

        /*
         * Идентификаторы операций, которые должны быть выполнены
         * раньше данной (предшественники в графе зависимостей).
         */
        private List<Long> predecessorOperationIds =
                new ArrayList<>();
    }
}
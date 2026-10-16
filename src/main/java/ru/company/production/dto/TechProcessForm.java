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

        /*
         * Контролируемые параметры операции. Вид и значение выбирают
         * из справочника технолога «Контролируемые параметры».
         */
        @Valid
        private List<ControlParamRow> controlParams =
                new ArrayList<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ControlParamRow {

        /*
         * Вид параметра («Внесение типа трубки»).
         */
        @NotNull(message = "Выберите контролируемый параметр")
        private Long controlParamId;

        /*
         * Значение параметра («1 слойная 14мм»).
         *
         * Заполнено только у параметра типа LIST. У TEXT_INPUT
         * варианта в справочнике нет, и значение приходит в textValue,
         * поэтому поле обязательным быть не может — это проверяет
         * сервис по типу параметра.
         */
        private Long controlParamValueId;

        /*
         * Значение параметра со свободным вводом («А-15»).
         *
         * Заполнено только у параметра типа TEXT_INPUT.
         */
        @Size(
                max = 255,
                message = "Значение параметра не должно превышать "
                        + "255 символов"
        )
        private String textValue;

        /*
         * Идентификатор ячейки — для режима «входящие ячейки»:
         * когда в названии параметра есть слово «ячейка», значение
         * выбирают из классификатора входящих ячеек датчика.
         */
        private Long cellClassifierId;

        /*
         * Отметка «обязательное поле».
         */
        private boolean mandatory;
    }
}
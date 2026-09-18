package ru.company.production.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Форма приоритета планирования.
 *
 * Код в форму не выводится: он генерируется автоматически (ПР-NNN)
 * и служит машинным идентификатором правила, тогда как все четыре
 * параметра — настройки, которые диспетчер правит по факту загрузки
 * производства.
 */
@Getter
@Setter
@NoArgsConstructor
public class OrderPriorityForm {

    @Size(max = 100, message = "Наименование: не более 100 символов")
    private String name;

    @NotNull(message = "Укажите запуск: количество дней до первых операций")
    @Min(value = 0, message = "Запуск: не меньше 0 дней")
    @Max(value = 365, message = "Запуск: не больше 365 дней")
    private Integer startDays;

    @NotNull(message = "Укажите допустимую загруженность участка")
    @Min(value = 1, message = "Допустимая загруженность: от 1%")
    @Max(value = 100, message = "Допустимая загруженность: до 100%")
    private Integer maxLoadPercent;

    @NotNull(message = "Укажите время межоперационных переходов")
    @Min(value = 0, message = "Переходы: не меньше 0 рабочих дней")
    @Max(value = 365, message = "Переходы: не больше 365 рабочих дней")
    private Integer transitionDays;

    @NotNull(message = "Укажите максимальную дату изготовления")
    @Min(value = 0, message = "Отсечка изготовления: не меньше 0 рабочих дней")
    @Max(value = 365, message = "Отсечка изготовления: не больше 365 рабочих дней")
    private Integer maxManufacturingDays;

    @Size(max = 40, message = "Не более 40 символов")
    private String badgeClass;
}

package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Форма заказчика.
 *
 * Справочник пока заводится одним наименованием: код
 * (ЗК-NNN) генерируется автоматически, остальные реквизиты
 * (контакт, телефон, e-mail, примечание) заполняются позже.
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerForm {

    @NotBlank(message = "Укажите наименование заказчика")
    @Size(max = 255, message = "Не более 255 символов")
    private String name;
}

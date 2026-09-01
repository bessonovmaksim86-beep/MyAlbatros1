package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateOperationForm {

    @NotBlank(message = "Введите код операции")
    @Pattern(
            regexp = "[A-Za-z0-9_-]+",
            message = "Код может содержать буквы, цифры, дефис и подчёркивание"
    )
    @Size(max = 50, message = "Код не должен превышать 50 символов")
    private String code;

    @NotBlank(message = "Введите название операции")
    @Size(max = 255, message = "Название не должно превышать 255 символов")
    private String name;

    @Size(max = 5000, message = "Описание слишком длинное")
    private String description;
}
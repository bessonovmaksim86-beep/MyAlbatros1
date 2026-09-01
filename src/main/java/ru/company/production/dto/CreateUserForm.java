package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import ru.company.production.entity.Role;

@Getter
@Setter
public class CreateUserForm {

    @NotBlank(message = "Введите имя пользователя")
    @Size(min = 3, max = 100, message = "Логин должен содержать от 3 до 100 символов")
    private String username;

    @NotBlank(message = "Введите пароль")
    @Size(min = 8, max = 100, message = "Пароль должен содержать не менее 8 символов")
    private String password;

    @NotNull(message = "Выберите роль")
    private Role role;
}
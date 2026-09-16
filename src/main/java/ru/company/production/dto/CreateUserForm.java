package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.company.production.entity.ProductionService;
import ru.company.production.entity.Role;
import ru.company.production.entity.RoleUser;


@Getter
@Setter
@NoArgsConstructor
public class CreateUserForm {

    @NotBlank(message = "Укажите ФИО")
    @Size(
            max = 255,
            message = "ФИО не должно превышать 255 символов"
    )
    private String fullName;

    @NotBlank(message = "Укажите должность")
    @Size(
            max = 255,
            message = "Должность не должна превышать 255 символов"
    )
    private String specialty;

    /**
     * Для администратора базы служба может быть пустой.
     */
    private Long serviceId;
    /**
     * Подразделение необязательно.
     */
    private RoleUser roleUser;
    /**
     * Подразделение необязательно.
     */
    private ProductionService productionService;
    /**
     * Подразделение необязательно.
     */
    private Long organizationUnitId;

    /**
     * Для администратора базы может быть пустым.
     */


    @NotBlank(message = "Укажите пароль")
    @Size(
            min = 6,
            max = 100,
            message = "Пароль должен содержать от 6 до 100 символов"
    )
    private String password;

    @NotNull(message = "Выберите роль")
    private Role role;
}
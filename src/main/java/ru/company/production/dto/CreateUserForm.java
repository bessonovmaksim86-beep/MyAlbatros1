package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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
     * Служба обязательна для всех, кроме администратора базы.
     */
    private Long serviceId;

    /**
     * Подразделение (цех, отдел, бюро).
     * Не обязательно для руководителя службы и для СГМ.
     */
    private Long organizationUnitId;

    /**
     * Руководитель службы, руководитель подразделения
     * или исполнитель.
     */
    private RoleUser roleUser;

    @NotNull(message = "Выберите роль")
    private Role role;

    @NotBlank(message = "Укажите пароль")
    @Size(
            min = 6,
            max = 100,
            message = "Пароль должен содержать от 6 до 100 символов"
    )
    private String password;
}

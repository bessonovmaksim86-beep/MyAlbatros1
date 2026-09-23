package ru.company.production.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Форма закрепления за сотрудником операции или рабочего места.
 *
 * Оба поля-ссылки присутствуют всегда: вид привязки выбирается
 * переключателем, и «неактивный» селект остаётся пустым. Именно
 * {@code bindingType} решает, какое из них считается заполненным, —
 * так форма переживает включённый/выключенный JavaScript одинаково.
 */
@Getter
@Setter
@NoArgsConstructor
public class WorkshopBindingForm {

    private Long employeeId;

    /** OPERATION или WORK_PLACE. */
    private String bindingType;

    private Long operationId;

    private Long workPlaceId;
}

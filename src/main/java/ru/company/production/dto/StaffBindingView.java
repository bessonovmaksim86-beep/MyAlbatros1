package ru.company.production.dto;

/**
 * Привязка сотрудника в списке «Сотрудники».
 *
 * @param id           идентификатор записи привязки — по нему она удаляется
 * @param kind         «Операция» или «Рабочее место»
 * @param code         код операции или рабочего места
 * @param name         наименования в одну строку
 * @param type         тип операции / тип рабочего места
 * @param executorRole роль исполнителя из справочника
 */
public record StaffBindingView(
        Long id,
        String kind,
        String code,
        String name,
        String type,
        String executorRole
) {
}

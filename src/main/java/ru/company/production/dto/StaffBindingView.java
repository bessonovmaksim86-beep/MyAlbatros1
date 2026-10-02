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
 * @param kitReceipt   операция получения комплектации: её закрепляют по
 *                     тому же подразделению, но роль «Исполнитель» она
 *                     не носит, поэтому в списке подписана отдельно
 */
public record StaffBindingView(
        Long id,
        String kind,
        String code,
        String name,
        String type,
        String executorRole,
        boolean kitReceipt
) {
}

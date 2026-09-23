package ru.company.production.dto;

import java.util.List;

/**
 * Сотрудник цеха в списке вместе с его привязками.
 *
 * @param id        идентификатор сотрудника
 * @param fullName  фамилия и инициалы для подписи
 * @param specialty должность
 * @param username  учётная запись
 * @param bindings  закреплённые операции и рабочие места
 */
public record StaffMemberView(
        Long id,
        String fullName,
        String specialty,
        String username,
        List<StaffBindingView> bindings
) {

    public int bindingCount() {
        return bindings == null ? 0 : bindings.size();
    }
}

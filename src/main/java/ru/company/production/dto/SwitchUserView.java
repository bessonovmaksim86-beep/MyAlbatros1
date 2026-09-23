package ru.company.production.dto;

/**
 * Учётная запись в списке роли: администратор входит не «в роль вообще»,
 * а под конкретного сотрудника, поэтому для каждой учётной записи нужна
 * собственная подпись и кнопка входа.
 *
 * @param id               идентификатор учётной записи для {@code /admin/switch}
 * @param displayName      ФИО либо логин, если ФИО не заполнено
 * @param username         логин — важен при совпадающих ФИО
 * @param specialty        должность из учётной записи
 * @param organizationUnit цех/отдел либо {@code null}, если не назначен
 */
public record SwitchUserView(
        Long id,
        String displayName,
        String username,
        String specialty,
        String organizationUnit
) {
}

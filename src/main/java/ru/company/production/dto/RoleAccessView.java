package ru.company.production.dto;

import java.util.List;

/**
 * Роль в разделе «Роли и вход»: сколько сотрудников её носят, куда они
 * попадают и под кого именно можно войти, поэтому администратору не нужно
 * помнить коды перечисления.
 *
 * @param role        код роли (имя перечисления)
 * @param displayName человекочитаемое название роли
 * @param userCount   число действующих учётных записей с этой ролью
 * @param moduleName  название модуля, интерфейс которого открывает роль
 * @param users       действующие учётные записи роли
 */
public record RoleAccessView(
        String role,
        String displayName,
        long userCount,
        String moduleName,
        List<SwitchUserView> users
) {

    /**
     * Войти можно только если у роли есть хотя бы одна действующая учётная
     * запись: входить «в пустую роль» не под чем.
     */
    public boolean canEnter() {
        return users != null && !users.isEmpty();
    }
}
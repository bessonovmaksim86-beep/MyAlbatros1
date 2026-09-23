package ru.company.production.dto;

/**
 * Признак того, что администратор временно работает в интерфейсе другой роли.
 *
 * Значение кладётся в сессию при подмене аутентификации и читается верхней
 * панелью всех модулей: пока администратор «в чужой роли», прав на {@code
 * /admin/**} у него нет, поэтому возврат должен быть виден на каждой странице,
 * а не только в админке.
 *
 * Обычный класс с геттерами, а не record: значение читается из Thymeleaf,
 * и явные getX-методы не зависят от поддержки record-аксессоров в SpEL.
 */
public final class ImpersonationInfo {

    private final String adminUsername;
    private final String roleDisplayName;
    private final String displayName;

    public ImpersonationInfo(
            String adminUsername,
            String roleDisplayName,
            String displayName
    ) {
        this.adminUsername = adminUsername;
        this.roleDisplayName = roleDisplayName;
        this.displayName = displayName;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public String getRoleDisplayName() {
        return roleDisplayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

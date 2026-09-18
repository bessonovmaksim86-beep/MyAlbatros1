package ru.company.production.dto;

public record AdminUserView(
        Long id,
        String username,
        String fullName,
        String specialty,
        String service,
        String organizationUnit,
        String roleUser,
        String roleName,
        boolean active
) {
}

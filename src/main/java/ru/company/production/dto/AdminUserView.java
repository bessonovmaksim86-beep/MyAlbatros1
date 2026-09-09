package ru.company.production.dto;

public record AdminUserView(
        Long id,
        String username,
        String fullName,
        String specialty,
        String organizationUnit,
        String roleName,
        boolean active,
        String password
) {
}
package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserType {

    SERVICE_HEAD("Руководитель службы"),

    SUBDIVISION_HEAD("Руководитель подразделения"),

    SERVICE_EMPLOYEE("Сотрудник службы");

    private final String displayName;
}
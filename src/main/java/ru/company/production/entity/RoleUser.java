package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RoleUser {

    SERVICE_HEAD("Руководитель службы"),

    HEAD_OF_WORKSHOP_OR_DEPARTMENT("Начальник цеха/отдела"),

    EXECUTOR("Исполнитель");


    private final String displayName;
}
package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrganizationUnitType {

    WORKSHOP("Цех"),
    DEPARTMENT("Служба");

    private final String displayName;
}

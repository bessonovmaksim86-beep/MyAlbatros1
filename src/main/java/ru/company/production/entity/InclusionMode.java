package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InclusionMode {

    REQUIRED("Обязательно входит"),
    OPTIONAL("Может входить");

    private final String displayName;
}
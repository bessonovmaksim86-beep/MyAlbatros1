package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {

    ADMIN("Администратор базы"),

    TECHNOLOGIST("Технологический отдел"),

    SDP("Производство"),

    SGP("Склад готовой продукции"),

    SGM("Служба главного метролога"),

    SC("Служба качества"),

    OMTSIK("СПФ"),

    DISPATCHER("Диспетчерский отдел");

    private final String displayName;
}
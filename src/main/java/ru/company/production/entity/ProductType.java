package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductType {

    DEVICE("Прибор"),
    CELL("Ячейка"),
    SENSOR("Датчик"),
    SYSTEM("Система");

    private final String displayName;
}
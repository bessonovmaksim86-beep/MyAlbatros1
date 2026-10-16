package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Тип контролируемого параметра.
 *
 * Определяет, как технолог заполняет значение параметра в операции:
 * выбирает из справочника вариантов или вписывает сам.
 *
 * LIST — параметр со списком вариантов («Проволока», «Тип трубки»):
 * варианты ведёт технолог в этом же справочнике, а в операции их
 * выбирают из выпадающего списка.
 *
 * TEXT_INPUT — параметр со свободным вводом («Номер ячейки»):
 * варианты не заводят, а исполнитель вписывает значение текстом.
 */
@Getter
@RequiredArgsConstructor
public enum ControlParamType {

    LIST("Список"),
    TEXT_INPUT("Поле ввода");

    private final String displayName;
}

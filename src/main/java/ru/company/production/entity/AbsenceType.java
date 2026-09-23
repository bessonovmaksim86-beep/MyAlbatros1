package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид отсутствия сотрудника в производственном календаре.
 */
@Getter
@RequiredArgsConstructor
public enum AbsenceType {

    VACATION("Отпуск", "vacation"),
    SICK("Больничный", "sick"),
    UNPAID("Отпуск за свой счёт", "unpaid"),

    /*
     * Нерабочий день: суббота, воскресенье или перенесённый праздник.
     * Отмечается так же, как отсутствие, — иначе календарь показывал бы
     * выходные только «серым фоном» и по ним нельзя было бы посчитать
     * загрузку цеха.
     */
    DAY_OFF("Выходной", "dayoff");

    private final String displayName;

    /** Часть css-класса: по нему день красится в цвет вида отсутствия. */
    private final String cssSuffix;
}
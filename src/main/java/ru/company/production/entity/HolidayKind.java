package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид нерабочего дня в справочнике администратора.
 *
 * Оба вида дают в календарях одну и ту же отметку {@code DAY_OFF}: для
 * расчёта загрузки цеха важно не то, почему день нерабочий, а то, что
 * сотрудник в него не работает. Различие нужно подписи — праздничные
 * дни руководитель ищет в календаре глазами, а субботы и воскресенья
 * просто должны быть закрашены.
 */
@Getter
@RequiredArgsConstructor
public enum HolidayKind {

    HOLIDAY("Праздничный день"),
    DAY_OFF("Выходной день");

    private final String displayName;
}

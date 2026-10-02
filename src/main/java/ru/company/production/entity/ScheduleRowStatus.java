package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Стадия строки графика заказа покупателя.
 *
 * Разделение принципиально: предварительный расчёт диспетчер смотрит,
 * но занятость подразделений он создавать не должен — иначе черновик,
 * который так и не внесли в план, заблокировал бы планирование
 * следующего заказа.
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleRowStatus {

    DRAFT("Предварительный", "draft"),
    CONFIRMED("Внесён в план", "work");

    private final String displayName;

    /** Модификатор CSS-класса бейджа в таблицах графика. */
    private final String badgeClass;
}

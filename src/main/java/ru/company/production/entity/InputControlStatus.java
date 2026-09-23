package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Ход проверки партии бюро входного контроля.
 *
 * Карточка существует ровно в двух состояниях: открытая («В работе»)
 * заполняется контролёром, закрытая остаётся только для чтения —
 * решение по партии меняют не правкой карточки, а новой проверкой.
 */
@Getter
@RequiredArgsConstructor
public enum InputControlStatus {

    IN_WORK("В работе", "work"),
    COMPLETED("Завершён", "done");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}

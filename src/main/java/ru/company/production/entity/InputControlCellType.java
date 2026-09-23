package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид строки с номером ячейки в карточке входного контроля.
 *
 * Оба списка живут в одной таблице: по составу они одинаковы (номер
 * партии), различается только смысл — «проверено выборочно» или
 * «забраковано».
 */
@Getter
@RequiredArgsConstructor
public enum InputControlCellType {

    SAMPLE("Проверено выборочно"),
    DEFECT("Забраковано");

    private final String displayName;
}

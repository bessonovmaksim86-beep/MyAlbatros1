package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус выполнения операции конкретным изделием (экземпляром).
 *
 * Операция может переделываться: возврат на доработку не стирает
 * прежнюю попытку, а добавляет новую строку со следующим номером
 * попытки (attempt). Прежняя при этом получает статус REWORKED —
 * так история переделок остаётся читаемой.
 *
 * Соответствует CHECK-ограничению chk_pso_status
 * (миграция V19).
 */
@Getter
@RequiredArgsConstructor
public enum ProductSerialOperationStatus {

    PLANNED("Запланирована", "draft"),
    IN_WORK("В работе", "work"),
    DONE("Выполнена", "done"),

    /*
     * Попытка отправлена на доработку: фактической даты у такой
     * строки нет — это держит ограничение chk_pso_reworked_has_no_actual.
     */
    REWORKED("На доработке", "rework");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}

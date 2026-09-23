package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Решение бюро входного контроля по забракованной партии.
 *
 * Решения ровно два и оба перемещают партию: ячейки либо остаются
 * на предприятии в изоляторе брака, либо возвращаются поставщику.
 * Третьего исхода для партии с подтверждённым браком нет — «пробыть»
 * её входной контроль не может.
 */
@Getter
@RequiredArgsConstructor
public enum InputControlDecision {

    QUARANTINE("Перемещение в изолятор брака", "danger"),
    RETURN_TO_SUPPLIER("Возврат поставщику", "attention");

    private final String displayName;

    /** Модификатор CSS-класса подписи решения. */
    private final String badgeClass;
}

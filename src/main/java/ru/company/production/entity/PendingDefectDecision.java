package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Решение по заявке на отбраковку.
 *
 * ДВА варианта:
 *   APPROVED_TO_QUARANTINE — подтвердить перемещение в изолятор брака;
 *   REJECTED_TO_WH         — брак не подтвердить, переместить
 *                            на склад полуфабрикатов.
 *
 * NULL означает, что заявка ещё ожидает решения.
 */
@Getter
@RequiredArgsConstructor
public enum PendingDefectDecision {

    APPROVED_TO_QUARANTINE("Подтвердить перемещение в изолятор брака", "danger"),
    REJECTED_TO_WH("Брак не подтвердить, переместить на склад полуфабрикатов", "success");

    private final String displayName;

    /** Модификатор CSS-класса подписи решения. */
    private final String badgeClass;
}

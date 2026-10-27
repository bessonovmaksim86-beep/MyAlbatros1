package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Решение контроллера по подтверждению дефекта забракованной ячейки.
 *
 * Отличается от {@link PendingDefectDecision}: то решение выбирает
 * НАПРАВЛЕНИЕ (изолятор либо склад), это — верит ли контролёр
 * заявке цеха вообще. Оба варианта заканчиваются изолятором брака:
 * подтверждённый дефект кладёт в изолятор ячейку, неподтверждённый —
 * её же, но с отметкой, что дефект не нашли. Разница для анализа
 * брака, а не для маршрута.
 *
 * NULL — решение не принято.
 */
@Getter
@RequiredArgsConstructor
public enum DefectConfirmed {

    CONFIRMED("Дефект подтверждён", "danger"),

    DENIED("Дефект не подтверждён", "success");

    private final String displayName;

    /** Модификатор CSS-класса подписи решения. */
    private final String badgeClass;
}

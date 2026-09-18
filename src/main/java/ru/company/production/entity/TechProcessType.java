package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Тип техпроцесса, по которому выпускается позиция заказа.
 *
 * «Базовое» — текущий (базовый) техпроцесс, привязанный к изделию
 * классификатора. Набор типов будет расширен позже; сейчас заказ
 * выпускается только по базовому техпроцессу изделия.
 */
@Getter
@RequiredArgsConstructor
public enum TechProcessType {

    BASIC("Базовое");

    private final String displayName;

    /** Тип по умолчанию: выпуск по базовому техпроцессу изделия. */
    public static TechProcessType orDefault(TechProcessType type) {
        return type == null ? BASIC : type;
    }
}

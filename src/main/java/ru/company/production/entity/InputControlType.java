package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид входного контроля, которым отмечена ячейка.
 *
 * Отметка ставится на незабракованные ячейки партии: то, что попало в
 * изолятор брака или возврат поставщику, контролем не отмечается —
 * такая ячейка брак, а не прошедшее приёмку изделие.
 *
 * Виден контроль или нет — спрашивают на кнопке: партию принимают
 * либо после визуального осмотра, либо после визуального и
 * технического контроля. Третьего вида пока нет.
 */
@Getter
@RequiredArgsConstructor
public enum InputControlType {

    VISUAL("Визуальный"),
    VISUAL_TECH("Визуальный и технический");

    private final String displayName;
}

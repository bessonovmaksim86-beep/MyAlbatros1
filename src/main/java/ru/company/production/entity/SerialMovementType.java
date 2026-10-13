package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Событие перемещения ячейки вокруг неизменного номера.
 *
 * Тип описывает «где побывал номер», а не «повторно ли»: повторность
 * цикла — производная от хронологии (сколько QUARANTINED прошло по
 * номеру), отдельного типа RE_QUARANTINED не заводим — иначе CHECK
 * разрастался бы, а «число циклов брака» всё равно считается COUNT'ом.
 */
@Getter
@RequiredArgsConstructor
public enum SerialMovementType {

    QUARANTINED("Перемещена в изолятор брака", "danger"),
    RETURNED_TO_SUPPLIER("Возвращена поставщику", "attention"),
    REPAIR_ORDERED("Включена в ремонтный ЗНП", "attention"),
    REPAIR_DONE("Ремонт принят", "ok"),

    /*
     * Ячейка взята из изолятора в ремонтный заказ-комплектующий
     * силами бюро входного контроля (V52).
     *
     * Пара к REPAIR_ORDERED: тот тип фиксирует намерение (диспетчер
     * включил номер в заказ на ремонт), этот — событие (БВК реально
     * снял ячейку с изолятора и провёл по комплектовочной операции).
     * Без него между «Ремонт принят» и исчезновением из изолятора
     * образовывалась дыра: по журналу нельзя было сказать, кто и когда
     * забрал ячейку в работу.
     */
    KITTED_TO_REPAIR_ZNP("Взята в ремонтный ЗНП", "ok"),

    REPAIR_CANCELLED("Ремонтный ЗНП отменён", "muted"),
    IN_PRODUCTION("Вошла в выпуск производства", "muted"),
    ACCEPTED("Принята на склад", "ok");

    private final String displayName;

    /** Модификатор CSS-класса метки события. */
    private final String badgeClass;
}
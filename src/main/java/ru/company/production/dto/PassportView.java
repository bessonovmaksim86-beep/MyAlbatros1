package ru.company.production.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Паспорт изделия: полный путь ячейки или подсборки вокруг одного номера.
 *
 * В отличие от сводки поиска, показывающей только текущие свойства
 * объекта, паспорт держит хронологию: каждое движение с датой,
 * исполнителем, документом-источником и состоянием ячейки НА МОМЕНТ
 * события. Текущие колонки реестра для этого не годятся — их
 * перечёркивает следующее движение, а руководителю службы качества и
 * складу нужно именно то, чем ячейка была, когда её изолировали или
 * забирали в ремонт.
 *
 * Номер существует и вне реестра: в изолятор попадают номера «всей
 * партией» ещё до оприходования, поэтому паспорт собирают по журналу, а
 * строка реестра — необязательный источник заголовка.
 *
 * Строки готовит сервис внутри транзакции: {@code open-in-view}
 * выключен, а подписи читают ленивые связи исполнителя и заказов.
 */
@Getter
@AllArgsConstructor
public class PassportView {

    /** Полный номер изделия — заголовок паспорта. */
    private final String fullNumber;

    /** Наименование изделия (или «номер вне реестра», если строки нет). */
    private final String itemLabel;

    /** Разновидность на последний известный момент: «Ячейка», «Подсборка». */
    private final String kindLabel;

    /** Статус реестра на последний известный момент. */
    private final String statusLabel;

    /** Строка реестра существует: номер оприходован или выпущен. */
    private final boolean registered;

    /** Дата первого события. */
    private final String firstMomentLabel;

    /** Дата последнего события. */
    private final String lastMomentLabel;

    /** Число попаданий в изолятор брака за всю жизнь номера. */
    private final long quarantineCycles;

    /** Число снятых ремонтных заказов. */
    private final long cancelledRepairs;

    /** Хронология сверху вниз: первое событие — первой строкой. */
    private final List<PassportEventRow> events;

    /** Одна строка хронологии. */
    @Getter
    @AllArgsConstructor
    public static class PassportEventRow {

        /** Человекочитаемое название события («Перемещена в изолятор брака»). */
        private final String title;

        /** Модификатор CSS-класса метки события. */
        private final String badgeClass;

        /** Дата и время события. */
        private final String momentLabel;

        /** Исполнитель отметки. */
        private final String actorLabel;

        /** Документ-источник: заказ на ремонт, заказ производства, приход. */
        private final String documentLabel;

        /** Причина или примечание (цепочка причин брака, причина перемещения). */
        private final String reasonLabel;

        /** Состояние ячейки на момент события. */
        private final String stateLabel;
    }
}

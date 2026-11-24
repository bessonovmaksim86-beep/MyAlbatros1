package ru.company.production.service.checklist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор содержимого Б24-таблицы, вставленного из буфера обмена.
 *
 * <p>Таблица проверяется на раскладке реального чек-листа: заголовок
 * «Чек-лист приборов по заказу покупателя № 469 от 20 ноября 2026»,
 * подписи шапки в одной колонке и их значения в следующей, строка
 * условных обозначений «(1) (2) (3)» под настоящими заголовками и
 * количество, дописанное в конец наименования («…, 1 шт»), — отдельной
 * колонки «Количество» в листе нет.
 *
 * <p>Значения шапки важнее самих позиций: заказчик и срок исполнения
 * определяют, кому принадлежит заказ и можно ли ставить его в план, а
 * номер заказа 1С — единственная ниточка к источнику после того, как
 * программа выдала заказу собственный номер.
 */
class B24TableParserTest {

    private final B24TableParser parser = new B24TableParser();

    /**
     * Лист «как есть» после Ctrl+C: объединённые ячейки при копировании
     * дают пустые колонки, поэтому подпись и значение разделены
     * табуляцией.
     */
    private static final String INSTRUMENT_SHEET = String.join("\n",
            "Чек-лист приборов по заказу покупателя № 469 от 20 ноября 2026",
            "Заказчик:\tООО \"Новые Технологии\"\t\t\t\t\t\t\t",
            "Дата отгрузки:\t22.01.2027\t\t\t\t\t\t\t",
            "\t\t\t\t\t\t\t\t",
            "№ п/п\tНаименование\tСер. №\tДата передачи на ПСИ\t"
                    + "Дата возврата в производство\tДата передачи на ПСИ\t"
                    + "Дата возврата в производство\t"
                    + "Дата передачи в ОТК и ОМО\tПрим.",
            "(1)\t(2)\t(3)\t(4)\t(5)\t(6)\t(7)\t(8)\t(9)\t(10)",
            "1\tСигнализатор СУР-16-GAP-150-G3/4-C-6-P-O-C-C-Ex db-45 "
                    + "(с кабельным вводом BLOCK 20KMP080 Ni Exd; "
                    + "с табличками: LSA-301; LSA-302), 1 шт\t352\t16/12/2026"
                    + "\t\t\t\t\t",
            "2\tСигнализатор СУР-16-GAP-150-G3/4-C-6-P-O-C-C-Ex db-45 "
                    + "(с кабельным вводом BLOCK 20KMP080 Ni Exd; "
                    + "с табличками: LSA-301; LSA-302), 1 шт\t353\t16/12/2026"
                    + "\t\t\t\t\t"
    );

    @Test
    @DisplayName("Реквизиты шапки читаются из разных колонок")
    void readsHeaderRequisites() {

        B24TableParser.ParsedTable parsed = parser.parse(INSTRUMENT_SHEET);

        assertEquals("ООО \"Новые Технологии\"", parsed.customerName());
        assertEquals(LocalDate.of(2027, 1, 22), parsed.dueDate());
    }

    @Test
    @DisplayName("Заказ 1С: номер из заголовка, дата — словом")
    void readsSourceOrderOfOneC() {

        B24TableParser.ParsedTable parsed = parser.parse(INSTRUMENT_SHEET);

        assertEquals("469", parsed.sourceOrderNumber());
        assertEquals(LocalDate.of(2026, 11, 20), parsed.sourceOrderDate());
        assertEquals("№ 469 от 20.11.2026", parsed.sourceOrderLabel());
    }

    @Test
    @DisplayName("Лист приборов не принимается за лист датчиков")
    void detectsInstrumentSheet() {

        assertTrue(parser.parse(INSTRUMENT_SHEET).deviceSheet());
    }

    @Test
    @DisplayName("Позиции берутся из наименования, строки-служебники — мимо")
    void readsPositionsFromNameSuffix() {

        B24TableParser.ParsedTable parsed = parser.parse(INSTRUMENT_SHEET);

        /*
         * Две позиции, а не четыре: строка «(1) (2) …» и пустая строка
         * перед заголовками в список изделий попасть не должны.
         */
        assertEquals(2, parsed.rows().size());

        ChecklistParser.ParsedRow first = parsed.rows().get(0);

        assertEquals(
                new BigDecimal("1"),
                first.qty().stripTrailingZeros()
        );
        assertEquals("PCS", first.unit());
        assertTrue(first.name().startsWith("Сигнализатор СУР-16-GAP"));
    }

    @Test
    @DisplayName("Лист датчиков оставляет позиции датчиками")
    void sensorSheetStaysSensor() {

        String sheet = String.join("\n",
                "Чек-лист датчиков по заказу покупателя № 470 от 03.12.2026",
                "Заказчик: ООО \"Ромашка\"",
                "Дата отгрузки: 30.12.2026",
                "№ п/п\tНаименование\tКол-во\tПрим.",
                "1\tДатчик уровня ультразвуковой ДУУ2М-12-1-15,00-0,15-ОМ1,5\t2\t"
        );

        B24TableParser.ParsedTable parsed = parser.parse(sheet);

        assertFalse(parsed.deviceSheet());

        /*
         * Подпись и значение в одной ячейке читаются так же верно, как
         * и в разных: «Заказчик: ООО "Ромашка"» — одна строка листа.
         */
        assertEquals("ООО \"Ромашка\"", parsed.customerName());
        assertEquals(LocalDate.of(2026, 12, 30), parsed.dueDate());
        assertEquals("470", parsed.sourceOrderNumber());

        /*
         * Здесь колонка «Кол-во» есть, и количество берётся из неё:
         * строка без единицы в наименовании не должна быть отброшена.
         */
        assertEquals(1, parsed.rows().size());
        assertEquals(
                new BigDecimal("2"),
                parsed.rows().get(0).qty().stripTrailingZeros()
        );
    }

    @Test
    @DisplayName("Дата без года не выдумывается, номер находится")
    void monthWithoutYearKeepsNumberOnly() {

        String sheet = String.join("\n",
                "Чек-лист приборов по заказу покупателя № 501 от 20 ноября",
                "№ п/п\tНаименование",
                "1\tСигнализатор С-1, 3 шт"
        );

        B24TableParser.ParsedTable parsed = parser.parse(sheet);

        assertEquals("501", parsed.sourceOrderNumber());
        assertNull(parsed.sourceOrderDate());
        assertEquals("№ 501", parsed.sourceOrderLabel());
    }

    @Test
    @DisplayName("Пустая вставка объясняется, а не падает")
    void blankInputExplained() {

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("   ")
        );

        assertTrue(error.getMessage().contains("Ctrl+C"));
    }

    @Test
    @DisplayName("Вставка без колонки наименования отклоняется")
    void missingNameColumnRejected() {

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("Сер. №\tДата\n352\t16/12/2026")
        );

        assertTrue(error.getMessage().contains("Наименование"));
    }

    @Test
    @DisplayName("Заголовок без строк изделий — ошибка, а не пустой заказ")
    void headerOnlyRejected() {

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("№ п/п\tНаименование\tКол-во")
        );

        assertTrue(error.getMessage().contains("количеством"));
    }
}

package ru.company.production.service.checklist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Иерархия чек-листа в разборе Б24-таблицы.
 *
 * <p>Номер строки («№ п/п») различает изделие и его состав: «1» —
 * изделие, «1.1» и «1.2» — состав изделия «1». Без этого признака
 * состав попал бы в заказ отдельными позициями, и заказчик получил бы
 * датчик и прибор вместо системы, из которой они состоят.
 */
class B24HierarchyParseTest {

    private final B24TableParser parser = new B24TableParser();

    /**
     * Лист со структурой: два изделия, у каждого по два состава —
     * так же, как в реальном чек-листе № 472.
     */
    private static final String SHEET = String.join("\n",
            "Чек-лист приборов по заказу покупателя № 472 от 23 ноября 2026",
            "Заказчик:\tАО «Транснефть – Приволга»",
            "Дата отгрузки:\t28.05.2027",
            "№ п/п\tНаименование\tСер. №\tПрим.",
            "(1)\t(2)\t(3)\t(4)",
            "1\tУровнемер многоканальный ДУУ10 исполнение 2-, 1 шт\t\t",
            "1.1\tУровнемер поплавковый ДУУ10-02-0-3,55-0-0-10-1-0-1-1-000-1-I"
                    + " (с фторопластовым покрытием ЧЭ), 1 шт\t\t",
            "1.2\tБлок сопряжения с датчиками БСД5Н, 1 шт\t\t",
            "2\tУровнемер многоканальный ДУУ10 исполнение 2-, 1 шт\t\t",
            "2.1\tУровнемер поплавковый ДУУ10-02-0-3,55-0-0-10-1-0-1-1-000-1-I"
                    + " (с фторопластовым покрытием ЧЭ), 1 шт\t\t",
            "2.2\tБлок сопряжения с датчиками БСД5Н, 1 шт\t\t"
    );

    @Test
    @DisplayName("Номер строки читается: целые — изделия, дробные — состав")
    void readsRowNumbers() {

        List<ChecklistParser.ParsedRow> rows = parser.parse(SHEET).rows();

        assertEquals(6, rows.size());

        ChecklistParser.ParsedRow first = rows.get(0);
        ChecklistParser.ParsedRow component = rows.get(1);

        assertEquals("1", first.number());
        assertFalse(first.isComponent());

        assertEquals("1.1", component.number());
        assertTrue(component.isComponent());
        assertEquals("1", component.parentNumber());
    }

    @Test
    @DisplayName("Состав группируется по своему изделию")
    void groupsComponentsByParent() {

        SystemSheetMatcher matcher = new SystemSheetMatcher(null);

        var byParent = matcher.componentsByParent(parser.parse(SHEET).rows());

        assertEquals(2, byParent.size());
        assertEquals(2, byParent.get("1").size());
        assertEquals(2, byParent.get("2").size());
        assertEquals("1.2", byParent.get("1").get(1).number());
    }

    @Test
    @DisplayName("Номер «5.10» — десятый состав, а не пять целых")
    void dottedNumberStaysText() {

        ChecklistParser.ParsedRow row = new ChecklistParser.ParsedRow(
                1, "Датчик ДПУ8, 1 шт",
                new java.math.BigDecimal("1"), "PCS", null, "5.10"
        );

        assertTrue(row.isComponent());
        assertEquals("5", row.parentNumber());
    }
}

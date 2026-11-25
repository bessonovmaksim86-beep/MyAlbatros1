package ru.company.production.service.checklist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверка извлечения примечаний из реальных обозначений чек-листов.
 */
class ChecklistNoteUtilsTest {

    @Test
    @DisplayName("Пустая колонка «Прим.» → особенности из скобок обозначения")
    void extractsExecutionDetailsFromParens() {
        String designation = "Уровнемер поплавковый ДУУ10-02-0-3,55-0-0-10-1-0-1-1-000-1-I (с фторопластовым покрытием ЧЭ и фторопластовыми втулками на поплавках; поплавок тип IV УНКР.305446.012; с кабельным вводом BLOCK 20s КБУ-МР 080 Ni; с комплектом УДСФ-В-100-2,5-1; с табличкой: \"2\"), 1 шт.";

        String note = ChecklistNoteUtils.resolveNote(null, designation);

        assertNotNull(note);
        assertTrue(note.contains("фторопластовым"));
        assertTrue(note.contains("УНКР.305446.012"));
    }

    @Test
    @DisplayName("Скобка с цифрами (1-4) не считается примечанием")
    void skipsNumericRangeInMiddle() {
        String designation = "Датчик уровня ультразвуковой ДУУ2М-01А-1-3,00-2,0-ОМ1,5**-1 (поплавок тип II УНКР.305446.012)";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNotNull(note);
        assertTrue(note.contains("поплавок тип II"));
    }

    @Test
    @DisplayName("Пустое обозначение → null")
    void returnsNullForEmptyName() {
        assertNull(ChecklistNoteUtils.extractExecutionDetails(""));
        assertNull(ChecklistNoteUtils.extractExecutionDetails(null));
    }

    @Test
    @DisplayName("Обозначение без скобок → null")
    void returnsNullForNoParens() {
        assertNull(ChecklistNoteUtils.extractExecutionDetails("ДУУ10-02-10-1-I"));
    }

    @Test
    @DisplayName("Колонка «Прим.» заполнена → она и используется")
    void prefersRawNoteColumn() {
        String result = ChecklistNoteUtils.resolveNote("Особый заказ", "ДУУ10-02-10-1-I");
        assertEquals("Особый заказ", result);
    }

    @Test
    @DisplayName("Датчик: cleanColumnNote — только колонка, особенности не дублируются")
    void sensorNoteDoesNotDuplicateDesignation() {
        String designation = "Уровнемер поплавковый ДУУ10-02-0-3,55-0-0-10-1-0-1-1-000-1-I"
                + " (с фторопластовым покрытием ЧЭ и фторопластовыми втулками на поплавках)"
                + ", 1 шт.";

        // Колонка «Прим.» пустая → примечания датчика нет,
        // особенности остаются только в обозначении.
        assertNull(ChecklistNoteUtils.cleanColumnNote(null));
        assertNull(ChecklistNoteUtils.cleanColumnNote(""));
        assertNull(ChecklistNoteUtils.cleanColumnNote("   "));

        // Особенности при этом извлекаются (для прибора/системы).
        assertNotNull(ChecklistNoteUtils.extractExecutionDetails(designation));
    }

    @Test
    @DisplayName("cleanColumnNote: дата и код партии — мусор, нормальный текст остаётся")
    void cleanColumnNoteKeepsRealText() {
        assertNull(ChecklistNoteUtils.cleanColumnNote("25.08.26 СГП"));
        assertNull(ChecklistNoteUtils.cleanColumnNote("25.08.26"));
        assertNull(ChecklistNoteUtils.cleanColumnNote("СГП"));
        assertEquals("Срок изготовления 45 суток",
                ChecklistNoteUtils.cleanColumnNote("Срок изготовления 45 суток"));
    }

    @Test
    @DisplayName("Короткий код партии в колонке «Прим.» — игнорируется, смотрим особенности")
    void shortCodeInNoteColumnIgnored() {
        String designation = "Датчик положения уровня ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(с втулкой G1 УНКР.302639.022; с кабельным вводом BLOCK 20sКМР045 Ni), 1 шт";

        String result = ChecklistNoteUtils.resolveNote("СГП", designation);

        assertNotNull(result);
        assertTrue(result.contains("с кабельным вводом"));
        assertFalse(result.contains("СГП"));
    }

    @Test
    @DisplayName("Дата в колонке «Прим.» — игнорируется, смотрим особенности")
    void dateInNoteColumnIgnored() {
        String designation = "Датчик положения уровня ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(с втулкой G1 УНКР.302639.022; с кабельным вводом BLOCK 20sКМР045 Ni), 1 шт";

        String result = ChecklistNoteUtils.resolveNote("25.08.26", designation);

        assertNotNull(result);
        assertTrue(result.contains("с кабельным вводом"));
    }

    @Test
    @DisplayName("Дата + код в колонке «Прим.» — игнорируется")
    void dateAndCodeIgnored() {
        String designation = "Датчик положения уровня ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(с втулкой G1 УНКР.302639.022; с кабельным вводом BLOCK 20sКМР045 Ni), 1 шт";

        String result = ChecklistNoteUtils.resolveNote("25.08.26 СГП", designation);

        assertNotNull(result);
        assertTrue(result.contains("с кабельным вводом"));
    }

    @Test
    @DisplayName("Обозначение ДУУ2М с параметром (1-4) → скобка с буквами в конце — примечание")
    void duu2mDesignation() {
        String designation = "Датчик уровня ультразвуковой ДУУ2М-01А-1-3,00-2,0-ОМ1,5**-1 (поплавок тип II УНКР.305446.012; груз утяжеляющий X)";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNotNull(note);
        assertTrue(note.contains("поплавок"));
        assertTrue(note.contains("УНКР.305446.012"));
    }

    @Test
    @DisplayName("Скобка в конце с цифрами и запятой — без букв → null")
    void skipsParensWithOnlyNumbers() {
        String designation = "СУР-10-0,57-ОМ1,5**-G1-55 (2)";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNull(note);
    }

    @Test
    @DisplayName("Дата вне скобок — хвост не тривиальный → null")
    void skipsDateOutsideParens() {
        String designation = "Датчик положения уровня ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(подключение к процессу G1; с втулкой G1 высотой 100мм УНКР.302639.022-02; "
                + "с кабельным вводом BLOCK 20sКМР045 Ni Exd) 25.08.26 СГП";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNull(note);
    }

    @Test
    @DisplayName("Дата в скобках + хвост количества → null (нет других скобок)")
    void dateInParensWithQuantityTail() {
        String designation = "Датчик положения уровня ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(25.08.26 СГП), 1 шт";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNull(note);
    }

    @Test
    @DisplayName("Два блока скобок: правый — дата, левый — особенности")
    void dateInRightParensRetreatsToLeft() {
        String designation = "Датчик положения уровня ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(с втулкой G1 УНКР.302639.022; с кабельным вводом BLOCK 20sКМР045 Ni) (25.08.26 СГП), 1 шт";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNotNull(note);
        assertTrue(note.contains("с кабельным вводом"));
        assertFalse(note.contains("25.08.26"));
    }

    @Test
    @DisplayName("Три блока скобок: два даты, правая — дата → откат к особенностям")
    void twoDatesRetreatsToFeatures() {
        String designation = "ДПУ5А-0,25-10,0-ОМ1,5**-1 "
                + "(с кабельным вводом BLOCK 20s) (15.07.26 А) (25.08.26 СГП), 1 шт";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNotNull(note);
        assertTrue(note.contains("с кабельным вводом"));
        assertFalse(note.contains("25.08.26"));
    }

    @Test
    @DisplayName("Пустая колонка «Прим.» + скобки с цифрами → null")
    void rawNoteEmptyParensOnlyNumbers() {
        String result = ChecklistNoteUtils.resolveNote("", "СУР-10-0,57-ОМ1,5**-G1-55 (2)");
        assertNull(result);
    }

    @Test
    @DisplayName("Два набора скобок: берётся последний — с буквами")
    void lastParenGroupWins() {
        String designation = "ДПУ5А-1,47-10,0-ОМ1,5**-1 (блок 20s КБУ-МР 080 Ni), 1 шт (комплект УДСФ-В-100-2,5-1)";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        // Последний блок скобок — «комплект УДСФ-В…»
        assertNotNull(note);
        assertTrue(note.contains("УДСФ"));
    }

    @Test
    @DisplayName("Результат не дублирует наименование — только скобочная часть")
    void noteDoesNotContainProductName() {
        String designation = "Датчик уровня ультразвуковой ДУУ2М-01А-1-3,00-2,0-ОМ1,5**-1 (поплавок тип II УНКР.305446.012)";

        String note = ChecklistNoteUtils.extractExecutionDetails(designation);

        assertNotNull(note);
        assertFalse(note.contains("Датчик уровня ультразвуковой"));
    }
}

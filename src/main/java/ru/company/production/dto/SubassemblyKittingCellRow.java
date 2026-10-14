package ru.company.production.dto;

/**
 * Строка ячейки (подсборки) склада полуфабрикатов в разделе
 * комплектования в ЗНП.
 *
 * Перечень склада и внесённый в заказ состав печатаются одними и теми
 * же строками: различие между ними — не отметка на ячейке, а сам факт
 * принадлежности. Проведённая в ЗНП ячейка со свободных полок снята,
 * поэтому она физически не может попасть в складской перечень, и
 * признак «уже внесена» был бы второй правдой о том же факте — при
 * расхождении перечней кладовщик получил бы номер одновременно
 * «свободным» и «внесённым».
 *
 * Ячейка держится полным номером, а не ссылкой на сущность: перечень
 * печатается при выключенном {@code open-in-view}, и обращение
 * шаблона к ленивой связи классификатора прервало бы рендеринг.
 */
public record SubassemblyKittingCellRow(
        Long serialId,
        String fullNumber,
        String displayName,
        String classifierCode,
        Long classifierId
) {

    /** Изделие короткой подписью: «1212 — Подсборка ЯПР56». */
    public String getCellLabel() {
        if (classifierCode == null || classifierCode.isBlank()) {
            return displayName == null || displayName.isBlank()
                    ? fullNumber
                    : displayName;
        }

        return displayName == null || displayName.isBlank()
                ? classifierCode
                : classifierCode + " — " + displayName;
    }

    /*
     * Двойники вида getX() держат рядом с аксессорами record
     * намеренно: Thymeleaf читает свойства по JavaBeans-соглашению
     * (get<Имя>() / is<Имя>()), а record компилируется с аксессором
     * <имя>() без префикса.
     */
    public String getFullNumber() {
        return fullNumber;
    }

    public String getDisplayName() {
        return displayName;
    }
}
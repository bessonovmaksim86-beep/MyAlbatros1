package ru.company.production.dto;

/**
 * Строка ячейки (подсборки) на складе полуфабрикатов в разделе
 * комплектования в ЗНП.
 *
 * Перечень складского остатка один для всех заказов страницы, а
 * признак «уже внесена в этот ЗНП» у каждого заказа свой, поэтому
 * строка переиспользуется через {@link #mark(boolean)} — копия с
 * отметкой вместо пересборки всего перечня на каждый заказ.
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
        boolean taken
) {

    /** Строка складского остатка: к заказу ещё не привязана. */
    public SubassemblyKittingCellRow(Long serialId,
                                     String fullNumber,
                                     String displayName,
                                     String classifierCode) {
        this(serialId, fullNumber, displayName, classifierCode, false);
    }

    /** Та же строка с отметкой «внесена в заказ». */
    public SubassemblyKittingCellRow mark(boolean takenFlag) {
        return new SubassemblyKittingCellRow(
                serialId, fullNumber, displayName, classifierCode, takenFlag
        );
    }

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

    /** Подпись состояния в колонке «Статус». */
    public String stateLabel() {
        return taken ? "внесена в заказ" : "можно внести";
    }

    /** Модификатор CSS-метки состояния — цвета прочего склада. */
    public String stateBadgeClass() {
        return taken ? "badge badge--success" : "badge";
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

    public boolean isTaken() {
        return taken;
    }
}
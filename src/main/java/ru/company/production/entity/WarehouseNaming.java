package ru.company.production.entity;

/**
 * Терминология склада в названиях изделий.
 *
 * До оприходования на склад полуфабрикатов позиция приходной
 * операции — подсборка: статус ячейки она получает только после
 * передачи. Справочник классификаторов при этом оперирует типом
 * «Ячейка», поэтому отображаемые названия приводятся к термину
 * «Подсборка», не меняя ни кодов, ни нумерации.
 */
public final class WarehouseNaming {

    private static final String CELL_WORD = "Ячейка";

    private static final String SUBASSEMBLY_WORD = "Подсборка";

    private WarehouseNaming() {
    }

    /**
     * «Ячейка А1» → «Подсборка А1», «ЯПР56» → «Подсборка ЯПР56».
     * Пустое название возвращается как есть: подставлять термин
     * к отсутствующему наименованию нечего.
     */
    public static String subassemblyLabel(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }

        String trimmed = name.trim();

        if (trimmed.regionMatches(
                true, 0, CELL_WORD, 0, CELL_WORD.length()
        )) {
            String rest = trimmed.substring(CELL_WORD.length()).trim();

            return rest.isEmpty()
                    ? SUBASSEMBLY_WORD
                    : SUBASSEMBLY_WORD + " " + rest;
        }

        return SUBASSEMBLY_WORD + " " + trimmed;
    }
}

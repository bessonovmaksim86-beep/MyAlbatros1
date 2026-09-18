package ru.company.production.dto;

/**
 * Помощник построения карточек-фильтров по типам.
 *
 * Цветовой модификатор выдаётся циклически (tfc-0 … tfc-7),
 * чтобы карточки любого количества выглядели согласованно.
 */
public final class TypeCards {

    private static final int PALETTE_SIZE = 8;

    private TypeCards() {
    }

    /**
     * Css-модификатор цвета карточки по её порядковому номеру.
     */
    public static String modifier(int index) {
        return "tfc-" + Math.floorMod(index, PALETTE_SIZE);
    }

    /**
     * Короткая иконка карточки — первая буква наименования типа.
     */
    public static String icon(String name) {
        if (name == null || name.isBlank()) {
            return "Т";
        }

        return name.substring(0, 1).toUpperCase();
    }
}
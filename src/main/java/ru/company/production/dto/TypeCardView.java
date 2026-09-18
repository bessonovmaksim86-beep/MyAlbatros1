package ru.company.production.dto;

/**
 * Карточка типа на главной странице модуля.
 *
 * Клик по карточке открывает список только этого типа,
 * поэтому код карточки одновременно является значением
 * параметра фильтрации в адресной строке.
 *
 * @param code     идентификатор типа в адресной строке
 * @param name     подпись карточки
 * @param count    количество записей этого типа
 * @param icon     короткая иконка (одна буква)
 * @param modifier часть css-класса цвета карточки
 */
public record TypeCardView(
        String code,
        String name,
        long count,
        String icon,
        String modifier
) {

    public String href() {
        return "?type=" + code;
    }
}

package ru.company.production.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Общая сводка по найденному объекту.
 *
 * Показывается в едином вне-ролевом интерфейсе поверх любого модуля:
 * поиск один на всю систему, а карточки разделов выглядят по-разному,
 * и переход прямо в чужую страницу сбивал пользователя. Сводка даёт
 * нейтральную «общую информацию по объекту», а в раздел, где его ведут,
 * ведёт отдельная ссылка {@link #moduleUrl}.
 *
 * Поля собраны сервисом плоскими строками «подпись — значение» внутри
 * транзакции: там ленивые связи читаются безопасно (open-in-view
 * выключен), а шаблон получает уже готовые строки и к сущности не
 * обращается.
 */
@Getter
@AllArgsConstructor
public class SearchSummaryView {

    /** Ключ категории: techprocess, classifier, operation, workplace, customerorder, productionorder. */
    private final String category;

    /** Заголовок объекта — номер заказа, код маршрута, изделие и т. п. */
    private final String title;

    /** Человекочитаемое имя категории для подписи под заголовком. */
    private final String categoryLabel;

    /** Короткая подпись-статус в шапке (например статус заказа). */
    private final String statusLabel;

    /** Адрес карточки объекта в его разделе — кнопка «Открыть в разделе». */
    private final String moduleUrl;

    /** Свойства объекта плоским списком, в порядке показа. */
    private final List<Field> fields;

    /** Одна строка сводки: подпись свойства и его значение. */
    @Getter
    @AllArgsConstructor
    public static class Field {

        private final String label;

        private final String value;
    }
}

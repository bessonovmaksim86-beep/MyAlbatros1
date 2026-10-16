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
 * нейтральную «общую информацию по объекту». Кнопка «Открыть в разделе»
 * убрана намеренно: она вела пользователя в чужой раздел, что было
 * небезопасно и сбивало с толку — теперь человек уходит в раздел сам,
 * по навигации, а не из поиска.
 *
 * Поля собраны сервисом плоскими строками «подпись — значение» внутри
 * транзакции: там ленивые связи читаются безопасно (open-in-view
 * выключен), а шаблон получает уже готовые строки и к сущности не
 * обращается.
 */
@Getter
public class SearchSummaryView {

    /** Ключ категории: techprocess, classifier, operation, workplace, customerorder, productionorder, serial. */
    private final String category;

    /** Заголовок объекта — номер заказа, код маршрута, изделие и т. п. */
    private final String title;

    /** Человекочитаемое имя категории для подписи под заголовком. */
    private final String categoryLabel;

    /** Короткая подпись-статус в шапке (например статус заказа). */
    private final String statusLabel;

    /** Свойства объекта плоским списком, в порядке показа. */
    private final List<Field> fields;

    /**
     * Адрес паспорта изделия либо {@code null}, если у объекта истории
     * перемещений нет.
     *
     * Ссылка отдельным полем, а не строкой свойств: значение поля —
     * текст, и номер ячейки, на который нельзя кликнуть, заставил бы
     * пользователя искать паспорт вручную.
     */
    private final String passportUrl;

    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields) {
        this(category, title, categoryLabel, statusLabel, fields, null);
    }

    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields,
                             String passportUrl) {
        this.category = category;
        this.title = title;
        this.categoryLabel = categoryLabel;
        this.statusLabel = statusLabel;
        this.fields = fields;
        this.passportUrl = passportUrl;
    }

    /** Одна строка сводки: подпись свойства и его значение. */
    @Getter
    @AllArgsConstructor
    public static class Field {

        private final String label;

        private final String value;
    }
}

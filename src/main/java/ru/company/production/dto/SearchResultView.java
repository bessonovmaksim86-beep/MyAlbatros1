package ru.company.production.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Строка результатов глобального поиска.
 *
 * Каждый результат несёт категорию, заголовок, уточняющую подпись и
 * адрес общей сводки по объекту.
 *
 * Сводка — единый вне-ролевой интерфейс, и переход в чужой раздел
 * из строки выдачи был небезопасен: пользователь мог попасть не в
 * свой раздел. Теперь поиск ведёт только в сводку, а в раздел
 * человек уходит сам — по навигации или кнопке на рабочем столе.
 */
@Getter
@AllArgsConstructor
public class SearchResultView {

    /**
     * Идентификатор найденной записи. Нужен общему интерфейсу
     * (category + id), чтобы прочитать объект и показать его сводку.
     */
    private final Long id;

    /**
     * Служебный ключ категории: techprocess, classifier, operation,
     * workplace, customerorder, productionorder, serial. Нужен клиенту
     * для группировки, а общему интерфейсу — для выбора, что читать.
     */
    private final String category;

    /**
     * Человекочитаемое имя категории для заголовка группы.
     */
    private final String categoryLabel;

    /**
     * Основное наименование результата.
     */
    private final String title;

    /**
     * Уточнение: код изделия, тип, род датчика и т. п.
     */
    private final String subtitle;

    /**
     * Адрес общей сводки по объекту — туда ведёт строка выдачи.
     */
    private final String url;
}
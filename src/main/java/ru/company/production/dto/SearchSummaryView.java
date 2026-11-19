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

    /**
     * Адрес основного действия по объекту: визуализация графа для
     * техпроцесса. Заполняется сервисом, а шаблон рисует кнопку.
     */
    private final String actionUrl;

    /**
     * Подпись кнопки основного действия.
     */
    private final String actionLabel;

    /**
     * Текущее место изделий заказа — вынесено из перечня свойств.
     *
     * Пустое для объектов, у которых движения нет (справочники,
     * заказ покупателя): плашка тогда не рисуется.
     */
    private final CurrentLocation currentLocation;

    /**
     * Матрица прохождения ТП: строки = операции маршрута,
     * колонки = ячейки (экземпляры), внесённые на эту операцию.
     *
     * Пустой, если заказ не ведён по техпроцессу или состав
     * ещё не внесён в план.
     */
    private final List<OperationPassage> operationPassages;

    /**
     * Сводка техпроцесса над перечнем изделий: код маршрута и
     * сколько работ закрыто из запланированных.
     */
    private final TechProcessSummary techProcessSummary;

    /**
     * Состав изделия: изделия, привязанные к классификатору.
     *
     * Заполняется только для изделия классификатора. Пусто — значит,
     * состав не описан, и секция не рисуется: прочерк вместо перечня
     * читался бы как потерянные данные.
     */
    private final List<CompositionItem> composition;

    /**
     * Заголовок секции состава.
     *
     * Разные типы изделия собирают состав по-разному: система — из
     * родов датчиков и приборов, прибор и ячейка — из входящих
     * изделий, поэтому заголовок задаёт сервис, а не шаблон.
     */
    private final String compositionTitle;

    /**
     * Текущее место: где изделия стоят прямо сейчас.
     *
     * Подпись строится по незакрытым строкам плана: если группа
     * разбросана по нескольким работам, показываются все, иначе
     * плашка соврала бы об адресе заказа.
     *
     * @param unitName    подразделение, ведущее основную работу;
     *                    пустое, если план адреса не задаёт
     * @param description готовая подпись места («Лакирование
     *                    подсборок — 3 яч.») либо объяснение, почему
     *                    места нет
     * @param others      остальные работы группы — подсказка, когда
     *                    изделия стоят не на одной работе
     */
    @Getter
    @AllArgsConstructor
    public static class CurrentLocation {

        private final String unitName;

        private final String description;

        private final String others;
    }

    /**
     * Сводка техпроцесса над перечнем изделий: код маршрута и
     * сколько работ закрыто из запланированных.
     */
    @Getter
    @AllArgsConstructor
    public static class TechProcessSummary {

        private final String code;

        private final String itemLabel;

        private final int operations;

        private final int passed;

        private final String progressLabel;
    }

    /**
     * Строка матрицы прохождения ТП: одна операция маршрута.
     *
     * Поля executor/plannedLabel/actualLabel/completed/overdue —
     * агрегированные по всем ячейкам этой операции.
     *
     * @param operationName  наименование операции
     * @param unitName       подразделение — цех, ведущий работу
     * @param executor       общий исполнитель (из первой незакрытой)
     * @param plannedLabel   плановая дата (первой строки)
     * @param actualLabel    факт (последней, если все закрыты)
     * @param completed      операция закрыта всеми ячейками
     * @param overdue        план вышел, есть незакрытые ячейки
     * @param statusLabel    подпись состояния для колонки статуса
     * @param statusModifier CSS-модификатор метки состояния
     * @param passed         сколько ячеек провело эту работу
     * @param total          сколько ячеек внесено в заказ
     * @param cells          ячейки (экземпляры), внесённые на эту
     *                       операцию, в порядке instanceIndex
     */
    @Getter
    @AllArgsConstructor
    public static class OperationPassage {

        private final String operationName;

        private final String unitName;

        private final String executor;

        private final String plannedLabel;

        private final String actualLabel;

        private final boolean completed;

        private final boolean overdue;

        private final String statusLabel;

        private final String statusModifier;

        private final int passed;

        private final int total;

        private final List<CellPassage> cells;

        /**
         * Готовность работы в процентах — ширина полосы прогресса.
         *
         * Считается здесь, а не в шаблоне: деление на общее число
         * ячеек при пустом составе дало бы ошибку, а проверять ноль
         * в Thymeleaf означало бы прятать правило готовности от
         * сервиса.
         */
        public int getProgressPercent() {
            return total == 0 ? 0 : passed * 100 / total;
        }
    }

    /**
     * Одна ячейка (экземпляр) на операции маршрута.
     *
     * @param serialId      идентификатор изделия — по нему клетка
     *                      матрицы ссылается на карточку номера
     * @param instanceIndex порядковый номер экземпляра (1-based)
     * @param fullNumber    полный номер изделия
     * @param kindLabel     «Ячейка» / «Подсборка»
     * @param executor      кто ведёт работу по этой ячейке
     * @param plannedLabel  плановая дата работы
     * @param actualLabel   фактическая дата; прочерк, пока работы нет
     * @param completed     ячейка провела эту операцию
     * @param overdue       план вышел, ячейка не провела
     */
    @Getter
    @AllArgsConstructor
    public static class CellPassage {

        private final Long serialId;

        private final int instanceIndex;

        private final String fullNumber;

        private final String kindLabel;

        private final String executor;

        private final String plannedLabel;

        private final String actualLabel;

        private final boolean completed;

        private final boolean overdue;

        /** Модификатор метки состояния клетки матрицы. */
        public String getStatusModifier() {
            if (completed) {
                return "ok";
            }

            return overdue ? "danger" : "muted";
        }

        /** Короткая подпись состояния для клетки. */
        public String getStatusLabel() {
            if (completed) {
                return "проведено";
            }

            return overdue ? "просрочено" : "ожидает";
        }
    }

    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields) {
        this(category, title, categoryLabel, statusLabel, fields,
                null, null, null, null, null, null, null, null);
    }

    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields,
                             String passportUrl) {
        this(category, title, categoryLabel, statusLabel, fields,
                passportUrl, null, null, null, null, null, null, null);
    }

    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields,
                             String passportUrl,
                             String actionUrl,
                             String actionLabel) {
        this(category, title, categoryLabel, statusLabel, fields,
                passportUrl, actionUrl, actionLabel, null, null, null,
                null, null);
    }

    /**
     * Сводка заказа с техпроцессом: состав не печатается, поэтому
     * обе строки состава приходятся {@code null}.
     *
     * Держится отдельным конструктором, чтобы вызов сводки заказа не
     * разрастался двумя лишними {@code null} в конце аргументов.
     */
    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields,
                             String passportUrl,
                             String actionUrl,
                             String actionLabel,
                             CurrentLocation currentLocation,
                             List<OperationPassage> operationPassages,
                             TechProcessSummary techProcessSummary) {
        this(category, title, categoryLabel, statusLabel, fields,
                passportUrl, actionUrl, actionLabel, currentLocation,
                operationPassages, techProcessSummary, null, null);
    }

    /**
     * Сводка с составом изделия.
     *
     * Отдельный конструктор, а не рост списка параметров у канонического:
     * состав нужен только классификатору, и передавать его шестью
     * {@code null} в каждую остальную категорию значило бы размывать
     * назначение сводки.
     */
    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields,
                             List<CompositionItem> composition,
                             String compositionTitle) {
        this(category, title, categoryLabel, statusLabel, fields,
                null, null, null, null, null, null,
                composition, compositionTitle);
    }

    public SearchSummaryView(String category,
                             String title,
                             String categoryLabel,
                             String statusLabel,
                             List<Field> fields,
                             String passportUrl,
                             String actionUrl,
                             String actionLabel,
                             CurrentLocation currentLocation,
                             List<OperationPassage> operationPassages,
                             TechProcessSummary techProcessSummary,
                             List<CompositionItem> composition,
                             String compositionTitle) {
        this.category = category;
        this.title = title;
        this.categoryLabel = categoryLabel;
        this.statusLabel = statusLabel;
        this.fields = fields;
        this.passportUrl = passportUrl;
        this.actionUrl = actionUrl;
        this.actionLabel = actionLabel;
        this.currentLocation = currentLocation;
        this.operationPassages = operationPassages;
        this.techProcessSummary = techProcessSummary;
        this.composition = composition;
        this.compositionTitle = compositionTitle;
    }

    /**
     * Одно изделие, входящее в состав классификатора.
     *
     * @param classifierId идентификатор классификатора — по нему строка
     *                     ссылается на его карточку в поиске
     * @param code         4-значный код изделия
     * @param name         наименование изделия
     * @param typeLabel    тип изделия («Ячейка», «Прибор», «Датчик»)
     * @param quantity     количество в составе
     * @param unitLabel    единица измерения количества
     * @param modeLabel    режим включения («Обязательно входит» /
     *                     «Может входить»); пусто, когда состав задан
     *                     без различения обязательности
     */
    @Getter
    @AllArgsConstructor
    public static class CompositionItem {

        private final Long classifierId;

        private final Integer code;

        private final String name;

        private final String typeLabel;

        private final String quantity;

        private final String unitLabel;

        private final String modeLabel;

        /** Подпись кода для таблицы: пусто превращается в прочерк. */
        public String getCodeLabel() {
            return code == null ? "—" : String.valueOf(code);
        }

        /** Ссылка на карточку изделия в нейтральном интерфейсе поиска. */
        public String getClassifierUrl() {
            return classifierId == null
                    ? null
                    : "/search/object/classifier/" + classifierId;
        }
    }

    /** Одна строка сводки: подпись свойства и его значение. */
    @Getter
    @AllArgsConstructor
    public static class Field {

        private final String label;

        private final String value;
    }
}

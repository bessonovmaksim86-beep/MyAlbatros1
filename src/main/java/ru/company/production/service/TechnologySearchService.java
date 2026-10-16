package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.SearchResultView;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.ClassifierSearchView;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OperationSearchView;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.TechProcessSearchView;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.repository.WorkPlaceSearchView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Универсальный поиск по сущностям производственной системы.
 *
 * Ищет одновременно по техпроцессам, изделиям классификатора,
 * операциям, рабочим местам, заказам (покупателя — ЗП, на
 * производство — ЗНП), серийным номерам (он же прибор, ячейка,
 * датчик, система) и приходам склада по приходному ордеру. Ответ —
 * плоский список: клиент сам группирует строки по полю category и
 * рисует заголовки только над непустыми группами.
 *
 * Заказы ищутся только по номеру: иного наименования у них нет, а
 * заказчик и статус — уточнение строки выдачи, а не признак поиска.
 * Разбором номера занимается {@link OrderSearch} — он же решает, какие
 * категории вообще опрашивать (см. его комментарии).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TechnologySearchService {

    private static final int PER_CATEGORY_LIMIT = 8;

    /**
     * Категория паспортов изделия. Отдельна от реестра, потому что
     * хронология ведётся и по номерам, которых в реестре уже нет:
     * выпущенное изделие из реестра уходит, а его перемещения остаются
     * в журнале и должны находиться по тому же номеру ячейки.
     */
    private static final String PASSPORT_CATEGORY = "Паспорта изделий (хронология)";

    private final TechProcessRepository techProcessRepository;
    private final ProductClassifierRepository classifierRepository;
    private final OperationRepository operationRepository;
    private final WorkPlaceRepository workPlaceRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductSerialRepository productSerialRepository;
    private final CellReceivingBatchRepository receivingBatchRepository;
    private final ProductPassportService passportService;

    public List<SearchResultView> search(String term) {
        Pageable pageable = PageRequest.of(0, PER_CATEGORY_LIMIT);
        List<SearchResultView> results = new ArrayList<>();

        OrderSearch orders = OrderSearch.of(term);

        /*
         * Строки заказов идут первыми: тот, кто набирает номер, ищет
         * именно заказ, и прятать его под списком из восьми техпроцессов
         * означало бы заставлять пользователя прокручивать выдачу.
         */
        if (orders.searchesCustomerOrders()) {
            results.addAll(
                    searchCustomerOrders(orders.pattern(), pageable)
            );
        }

        if (orders.searchesProductionOrders()) {
            results.addAll(
                    searchProductionOrders(orders.pattern(), pageable)
            );
        }

        /*
         * Серийники — сразу за заказами: набранный девятизначный номер
         * и есть искомый экземпляр, и терять его ниже перечня
         * техпроцессов нельзя.
         */
        if (orders.searchesSerials()) {
            results.addAll(searchSerials(term, pageable));
            results.addAll(searchPassports(term, pageable));
        }

        if (orders.searchesReceiving()) {
            results.addAll(searchReceiving(term, pageable));
        }

        if (orders.searchesCatalog()) {
            results.addAll(searchTechProcesses(term, pageable));
            results.addAll(searchClassifiers(term, pageable));
            results.addAll(searchOperations(term, pageable));
            results.addAll(searchWorkPlaces(term, pageable));
        }

        return results;
    }

    private List<SearchResultView> searchTechProcesses(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (TechProcessSearchView view :
                techProcessRepository.search(term, pageable)) {

            results.add(new SearchResultView(
                    view.getId(),
                    "techprocess",
                    "Техпроцессы",
                    view.getCode(),
                    classifierSignature(
                            view.getClassifierCode(),
                            view.getClassifierName(),
                            view.getSensorClassName()
                    ),
                    summaryUrl("techprocess", view.getId())
            ));
        }

        return results;
    }

    private List<SearchResultView> searchClassifiers(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (ClassifierSearchView view :
                classifierRepository.search(term, pageable)) {

            results.add(new SearchResultView(
                    view.getId(),
                    "classifier",
                    "Классификаторы",
                    classifierTitle(view),
                    view.getProductType().getDisplayName(),
                    summaryUrl("classifier", view.getId())
            ));
        }

        return results;
    }

    private List<SearchResultView> searchOperations(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (OperationSearchView view :
                operationRepository.search(term, pageable)) {

            results.add(new SearchResultView(
                    view.getId(),
                    "operation",
                    "Операции",
                    view.getOperationName(),
                    view.getOperationTypeName(),
                    summaryUrl("operation", view.getId())
            ));
        }

        return results;
    }

    private List<SearchResultView> searchWorkPlaces(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (WorkPlaceSearchView view :
                workPlaceRepository.search(term, pageable)) {

            results.add(new SearchResultView(
                    view.getId(),
                    "workplace",
                    "Рабочие места",
                    view.getCode() + " — " + view.getName(),
                    view.getPlaceTypeName(),
                    summaryUrl("workplace", view.getId())
            ));
        }

        return results;
    }

    private List<SearchResultView> searchCustomerOrders(
            String pattern,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (CustomerOrder order :
                customerOrderRepository.search(pattern, pageable)) {

            results.add(new SearchResultView(
                    order.getId(),
                    "customerorder",
                    "Заказы покупателя (ЗП)",
                    order.getNumber(),
                    signature(
                            order.getCustomer() == null
                                    ? null
                                    : order.getCustomer().getName(),
                            order.getStatus() == null
                                    ? null
                                    : order.getStatus().getDisplayName()
                    ),
                    summaryUrl("customerorder", order.getId())
            ));
        }

        return results;
    }

    private List<SearchResultView> searchProductionOrders(
            String pattern,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (ProductionOrder order :
                productionOrderRepository.search(pattern, pageable)) {

            /*
             * Уровень берётся из getLevelDisplayName(): выпуск и
             * заказ-комплектующий носят номера одного шаблона, поэтому
             * различить их в выдаче может только сам заказ.
             */
            results.add(new SearchResultView(
                    order.getId(),
                    "productionorder",
                    "Заказы на производство (ЗНП)",
                    order.getNumber(),
                    signature(
                            order.getLevelDisplayName(),
                            order.getStatus() == null
                                    ? null
                                    : order.getStatus().getDisplayName()
                    ),
                    summaryUrl("productionorder", order.getId())
            ));
        }

        return results;
    }

    /*
     * Строки реестра серийных номеров.
     *
     * Один и тот же реестр держит приборы, ячейки, датчики и системы,
     * поэтому группа называется по признаку «серийный номер», а не по
     * типу: тип видно в подписи строки и различает изделия.
     */
    private List<SearchResultView> searchSerials(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (ProductSerialRepository.SerialSearchView view :
                productSerialRepository.search(term, pageable)) {

            results.add(new SearchResultView(
                    view.getId(),
                    "serial",
                    "Серийные номера",
                    fullNumber(view),
                    signature(serialItem(view), serialFoundBy(view)),
                    summaryUrl("serial", view.getId())
            ));
        }

        return results;
    }

    /*
     * Паспорта изделия — хронология перемещений по номеру ячейки.
     *
     * Источник — журнал перемещений, а не реестр: в нём остаются и
     * номера, выпущенные из реестра, и номера, в реестр никогда не
     * попадавшие (например, снятые с изделия при ремонте). Идентификатор
     * строке не нужен: паспорт адресуется самим номером.
     */
    private List<SearchResultView> searchPassports(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (String fullNumber : passportService.searchNumbers(
                term, pageable.getPageSize())) {

            results.add(new SearchResultView(
                    null,
                    "passport",
                    PASSPORT_CATEGORY,
                    fullNumber,
                    "Хронология перемещений ячейки",
                    "/passport/" + fullNumber
            ));
        }

        return results;
    }

    /*
     * Строки приходов склада.
     *
     * Партия может быть ещё не подтверждена сканированием, и её
     * номеров в реестре нет, — приход ищется по своей таблице, чтобы
     * оформленный, но незакрытый приход находился.
     */
    private List<SearchResultView> searchReceiving(
            String term,
            Pageable pageable
    ) {
        List<SearchResultView> results = new ArrayList<>();

        for (CellReceivingBatchRepository.ReceivingSearchView view :
                receivingBatchRepository.search(term, pageable)) {

            results.add(new SearchResultView(
                    view.getId(),
                    "receiving",
                    "Приходы склада (приходный ордер)",
                    view.getIncomingOrderNumber(),
                    signature(
                            view.getSupplierName(),
                            receivingCell(view)
                    ),
                    summaryUrl("receiving", view.getId())
            ));
        }

        return results;
    }

    /* ============================================================
       РАЗБОР ЗАПРОСА ПО НОМЕРУ ЗАКАЗА
       ============================================================ */

    /*
     * Что пользователь мог ввести, чтобы искать заказ.
     *
     * Номера в базе строковые и содержат буквенную часть
     * («ЗП-2026-005», «ЗНП-2026-014»), поэтому «префикс + число» в лоб
     * не работает: между префиксом и номером стоит дефис и год.
     *
     * Правило, из которого исходим:
     *
     *   * «ЗП…», «ЗНП…», «ЗП-2026-005» — явный префикс: ищем ОДНУ
     *     соответствующую таблицу заказов и больше ничего. Режим точный:
     *     ввод обязан оборваться на конце номера, поэтому завершающий
     *     «%» не дописывается — «ЗП%57» находит «ЗП-2026-057» и не
     *     находит «ЗП-2026-571»;
     *   * «57», «2026-005» — только цифры: гибрид. Номера обоих типов
     *     устроены одинаково, и пользователь, помнящий «номер 57», не
     *     обязан помнить тип заказа, поэтому спрашиваем обе таблицы.
     *     Справочники тоже опрашиваем: набор цифр — это и код изделия;
     *   * «ТП-14», «ЯПР56» — буквы вне префикса: заказы трогать
     *     бессмысленно, остаётся прежний поиск по справочникам.
     *
     * Разделители ввода («ЗП 2026 005», «ЗП/2026/005») значимой частью
     * номера не считаются и заменяются на «%»: поиск не обязан угадывать,
     * как именно пользователь расставил дефисы.
     */
    private record OrderSearch(
            String pattern,
            boolean customerOrders,
            boolean productionOrders,
            boolean catalog,
            boolean serials,
            boolean receiving
    ) {

        private static final String CUSTOMER_PREFIX = "ЗП";
        private static final String PRODUCTION_PREFIX = "ЗНП";

        /*
         * Разделители частей номера. Дефис в наборе стоит последним:
         * внутри символьного класса он на краю читается как обычный
         * символ, а не как диапазон.
         */
        private static final Pattern SEPARATORS =
                Pattern.compile("[\\s./-]+");

        /*
         * Символы, значимые для LIKE, и обратная косая черта. В номерах
         * заказов их нет, а без удаления «%» из ввода пользователь
         * получил бы выдачу «все заказы сразу», то есть весь реестр.
         */
        private static final Pattern WILDCARDS =
                Pattern.compile("[%_*\\\\]");

        private static final OrderSearch CATALOG_ONLY =
                new OrderSearch(null, false, false, true, true, true);

        /*
         * Признаки читаются методами, а не полями: вызов обязан говорить
         * «искать ли эту категорию», а не просто называть поле.
         */
        boolean searchesCustomerOrders() {
            return customerOrders;
        }

        boolean searchesProductionOrders() {
            return productionOrders;
        }

        boolean searchesCatalog() {
            return catalog;
        }

        boolean searchesSerials() {
            return serials;
        }

        boolean searchesReceiving() {
            return receiving;
        }

        static OrderSearch of(String term) {

            String compact = WILDCARDS
                    .matcher(term)
                    .replaceAll("")
                    .replace("№", "")
                    .toUpperCase(Locale.ROOT);

            List<String> groups = new ArrayList<>();

            for (String part : SEPARATORS.split(compact)) {
                if (!part.isBlank()) {
                    groups.add(part);
                }
            }

            if (groups.isEmpty()) {
                return CATALOG_ONLY;
            }

            /*
             * Части номера склеиваются через «%»: пользователь вправе
             * написать «ЗП-2026-57» вместо «ЗП-2026-057» и получить свой
             * заказ — расхождение только в пропущенных символах внутри
             * части, а не в его номере.
             */
            String joined = String.join("%", groups);

            String first = groups.get(0);

            if (first.startsWith(PRODUCTION_PREFIX)) {
                /*
                 * Номер ЗНП носит и изделие: оператор вписывает его в
                 * серийник при выпуске (V50). Тот, кто набирает ЗНП,
                 * ищет и заказ, и выпущенные под ним номера, поэтому
                 * реестр опрашивается вместе с таблицей заказов.
                 */
                return prefixSearch(
                        PRODUCTION_PREFIX,
                        joined,
                        false,
                        true,
                        true,
                        false
                );
            }

            /*
             * Комплектующий проверяется первым: оба префикса начинаются
             * с «З», и более длинный вариант обязан разбираться до
             * короткого, иначе новый тип съел бы чужой ввод.
             */
            if (first.startsWith(CUSTOMER_PREFIX)) {
                return prefixSearch(
                        CUSTOMER_PREFIX,
                        joined,
                        true,
                        false,
                        false,
                        false
                );
            }

            if (groups.stream().allMatch(OrderSearch::isDigits)) {
                /*
                 * Гибридный режим: «помню только номер 57». Обрамляем
                 * «%» с обеих сторон — тот же заказ мог быть записан
                 * «ЗП-2026-571», и отбросить его по завершающему символу
                 * значило бы не найти то, что человек искал.
                 */
                return new OrderSearch(
                        "%" + joined + "%",
                        true,
                        true,
                        true,
                        true,
                        true
                );
            }

            return CATALOG_ONLY;
        }

        /*
         * Шаблон для явного префикса: сам префикс остаётся точным
         * началом номера, дальше — «%» и остаток ввода.
         *
         * Пустой остаток («просто ЗП») превращается в «ЗП%»: под запрос
         * подходит весь реестр заказов этого типа с ограничением по
         * строкам, и пользователь видит перечень вместо пустого ответа.
         */
        private static OrderSearch prefixSearch(
                String prefix,
                String joined,
                boolean customer,
                boolean production,
                boolean serials,
                boolean receiving
        ) {
            String tail = joined.substring(prefix.length());

            if (tail.startsWith("%")) {
                tail = tail.substring(1);
            }

            String pattern = tail.isEmpty()
                    ? prefix + "%"
                    : prefix + "%" + tail;

            return new OrderSearch(
                    pattern,
                    customer,
                    production,
                    false,
                    serials,
                    receiving
            );
        }

        private static boolean isDigits(String value) {
            return !value.isEmpty()
                    && value.chars().allMatch(Character::isDigit);
        }
    }

    /*
     * Общий адрес сводки по найденному объекту.
     *
     * Все строки выдачи ведут сюда, а не в раздел: поиск один на всю
     * систему, а интерфейсы разделов разные, и переход прямо в чужую
     * страницу сбивал пользователя.      * Сводка показывает найденное в
     * едином интерфейсе, а в раздел человек уходит сам — по навигации.
     */
    private String summaryUrl(String category, Long id) {
        return "/search/object/" + category + "/" + id;
    }

    /*
     * Подпись строки выдачи из двух уточнений: пустая часть
     * отбрасывается, чтобы в списке не появлялось «Иван · » или « ·
     * Новый» — заказ без заказчика или статуса встречается в старых
     * данных, и выдача не должна выглядеть сломанной.
     */
    private String signature(String first, String second) {
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasSecond = second != null && !second.isBlank();

        if (hasFirst && hasSecond) {
            return first + " · " + second;
        }

        if (hasFirst) {
            return first;
        }

        return hasSecond ? second : "";
    }

    /*
     * Заголовок изделия: у датчика собственного наименования нет,
     * поэтому показываем род из справочника.
     */
    private String classifierTitle(ClassifierSearchView view) {
        String name = view.getName() != null
                && !view.getName().isBlank()
                ? view.getName()
                : view.getSensorClassName();

        return view.getCode() + " " + (name != null ? name : "");
    }

    /*
     * Подпись строки техпроцесса: «1212 ЯПР56» либо «1212 ДТМ3»
     * для изделия-датчика, где наименования нет.
     */
    private String classifierSignature(
            Integer code,
            String name,
            String sensorName
    ) {
        String title = name != null && !name.isBlank()
                ? name
                : sensorName;

        return code + (title != null ? " " + title : "");
    }

    /*
     * Полный номер изделия из строки поиска: код классификатора плюс
     * пятизначная серия. Формула повторяет ProductSerial#getFullNumber():
     * выдача поиска и реестр обязаны показывать номер одинаково, иначе
     * найденная строка не совпадёт с тем, что человек видит в карточке.
     */
    private String fullNumber(
            ProductSerialRepository.SerialSearchView view
    ) {
        Integer code = view.getClassifierCode();
        Integer serial = view.getSerial();

        if (serial == null) {
            return code == null ? "" : String.valueOf(code);
        }

        return (code == null ? "" : code)
                + String.format("%05d", serial);
    }

    /* Наименование изделия серийника: у датчика — род из справочника. */
    private String serialItem(
            ProductSerialRepository.SerialSearchView view
    ) {
        String name = view.getClassifierName() != null
                && !view.getClassifierName().isBlank()
                ? view.getClassifierName()
                : view.getSensorClassName();

        if (name != null && !name.isBlank()) {
            return name;
        }

        return view.getProductType() == null
                ? null
                : view.getProductType().getDisplayName();
    }

    /*
     * Чем именно нашёлся серийник: приходным ордером или номером ЗНП.
     *
     * Строка выдачи обязана объяснять попадание: найденный по ордеру
     * номер выглядит иначе, чем найденный по своему номеру, и без
     * подписи человек не понял бы, при чём тут партия прихода.
     */
    private String serialFoundBy(
            ProductSerialRepository.SerialSearchView view
    ) {
        String order = view.getIncomingOrderNumber();

        if (order != null && !order.isBlank()) {
            return "ордер " + order;
        }

        String znp = view.getZnpNumber();

        return znp == null || znp.isBlank()
                ? null
                : "ЗНП " + znp;
    }

    /* Ячейка прихода: «1212 Подсборка ЯПР56» либо род датчика. */
    private String receivingCell(
            CellReceivingBatchRepository.ReceivingSearchView view
    ) {
        String name = view.getClassifierName() != null
                && !view.getClassifierName().isBlank()
                ? view.getClassifierName()
                : view.getSensorClassName();

        Integer code = view.getClassifierCode();

        if (code == null) {
            return name;
        }

        return name == null || name.isBlank()
                ? String.valueOf(code)
                : code + " " + name;
    }
}
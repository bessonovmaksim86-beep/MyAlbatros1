package ru.company.production.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.dto.KittingZnpOperationRow;
import ru.company.production.dto.KittingZnpSerialRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ClassifierInclusion;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.ClassifierInclusionRepository;
import ru.company.production.repository.KittingZnpLinkView;
import ru.company.production.repository.OrganizationUnitRepository;     
import ru.company.production.repository.OrderScheduleRowRepository;     
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.ZnpCellLinkView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Комплектование склада: операции, которые планировщик создал при
 * планировании заказа покупателя.
 *
 * Раздела три, и все видны каждой роли склада, потому что склады
 * работают по одному маршруту и различаются только подразделением:
 * склад комплектования собирает ЗНП, склад полуфабрикатов отдаёт
 * полуфабрикаты, а третий раздел добирает работу того же склада
 * комплектования, под которую заказ-комплектующий ещё не поднят.
 * Содержимое при этом всегда берётся по подразделению самого раздела, а
 * не по роли вошедшего: кладовщик, открывший чужой раздел, видит работу
 * того склада, на который смотрит, — так реестр складов остаётся
 * читаемым для обоих.
 *
 * Работой склада считаются получение комплектации и комплектовочная
 * операция: их планировщик закрепляет за подразделением, а не за
 * человеком, и цеховые этапы маршрута в раздел не попадают.
 */
@Service
@RequiredArgsConstructor
public class WarehouseKittingService {

    /**
     * Раздел комплектования: его подразделение склада, адрес и подписи.
     *
     * Код подразделения — единственный признак границы. Он берётся из
     * справочника, а не из учётной записи вошедшего: раздел обязан
     * показать работу своего склада даже тогда, когда у кладовщика
     * подразделение в карточке не заполнено.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Section {

        /** Склад комплектования собирает заказы-комплектующие (ЗНП). */
        ZNP(
                "ASSEMBLY_WAREHOUSE",
                "Комплектование ЗНП",
                "Заказы-комплектующие склада комплектования: изделия, "
                        + "которые нужно собрать из комплектации выпуска",
                "kitting-znp",
                "warehouse/kitting/znp",
                "znp"
        ),

        /** Склад полуфабрикатов отдаёт полуфабрикаты в сборку. */
        SEMI_FINISHED(
                "SEMI_FINISHED_PRODUCT_WAREHOUSE",
                "Комплектование полуфабрикатов",
                "Полуфабрикаты склада: изделия, которые склад выдаёт "
                        + "в сборку по плану заказа покупателя",
                "kitting-semi",
                "warehouse/kitting/semi",
                "semi"
        ),

        /**
         * Комплектование без заказ-комплектующего: работа склада, под
         * которую диспетчер заказа не поднимал.
         *
         * Раздел обязан существовать, потому что прежняя граница
         * списка — «есть поднятый заказ» — прятала от склада всю
         * остальную его работу: строку плана планировщик создаёт при
         * планировании заказа покупателя, а заказ-комплектующий
         * диспетчер заводит позже и не для каждой позиции. Пока заказа
         * нет, кладовщик операцию не видел вовсе и не мог её провести,
         * а загрузка склада выглядела нулевой.
         *
         * Состав здесь неведом: вносить номер не в какой заказ, поэтому
         * колонки состава и поля ввода у раздела нет, а работа
         * закрывается одним фактом.
         */
        PLAIN(
                "ASSEMBLY_WAREHOUSE",
                "Комплектование без заказа",
                "Операции комплектования склада, под которые "
                        + "заказ-комплектующий ещё не поднят: проводятся "
                        + "одним фактом, без внесения состава",
                "kitting-plain",
                "warehouse/kitting/plain",
                "plain"
        );

        private final String unitCode;
        private final String title;
        private final String description;
        private final String activePage;
        private final String view;

        /**
         * Идентификатор раздела в адресной строке.
         *
         * Поле, а не вычисление от числа разделов: адрес обязан
         * оставаться прежним при добавлении следующего раздела, иначе
         * закладки кладовщиков и ссылки шапки перестанут открывать
         * нужный склад.
         */
        private final String param;

        public String param() {
            return param;
        }
    }

    /**
     * Типы операций, составляющие комплектование склада.
     *
     * Получение комплектации — работа по подбору комплектов под
     * выпуск, комплектовочная операция — работа по заказу-комплектующему
     * (V15). Оба типа закреплены планом за складом, поэтому раздел
     * обязан показать и те, и другие: выбор одного из них оставил бы
     * половину работы склада невидимой.
     */
    private static final List<String> KITTING_TYPE_CODES = List.of(
            OperationTypeCode.KIT_RECEIPT.name(),
            OperationTypeCode.KITTING.name()
    );

    private final OrganizationUnitRepository organizationUnitRepository;
    private final WorkshopDistributionService distributionService;
    private final WorkshopDistributionSerialService serialService;
    private final SerialProductionOrderRepository serialProductionOrderRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final ProductSerialRepository serialRepository;
    private final ProductClassifierRepository classifierRepository;
    private final ClassifierInclusionRepository inclusionRepository;
    private final ProductionOrderItemRepository itemRepository;
    private final SerialMovementJournalService journal;

    /** Подпись даты проведения в раскрытии изделия. */
    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * Длина полного номера изделия: код классификатора (4 цифры) плюс
     * серия (5 цифр) — тот же расчёт, что в реестре и глобальном
     * поиске (V17).
     */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Подпись внесения в матрицу — общая с разделом подсборок. */
    private static final String LINK_NOTE =
            "Комплектование ЗНП на складе";

    /**
     * Операции комплектования раздела за горизонт планирования.
     *
     * Пустой список, если подразделение склада в справочнике
     * отсутствует: раздел тогда объясняет отсутствие работы, а не
     * показывает чужую.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findOperations(Section section) {
        List<Long> unitIds = unitIds(section);

        return unitIds.isEmpty()
                ? List.of()
                : distributionService.findKittingOperations(
                        unitIds, KITTING_TYPE_CODES
                );
    }

    /**
     * Операции комплектования без заказ-комплектующего.
     *
     * Строки плана планировщик создаёт при планировании заказа
     * покупателя, а заказ-комплектующий диспетчер заводит позже и не
     * для каждой позиции. Пока заказа нет, кладовщик операцию не
     * видел: в разделе «Комплектование ЗНП» она не появляется, потому
     * что там отбор идёт по наличию привязки «серийник + операция →
     * ЗНП».
     *
     * Этот раздел показывает именно такие строки — без ЗНП. Состав
     * здесь неведом: вносить номер не в какой заказ, поэтому работа
     * закрывается одним фактом проведения, без проверки ячеек и
     * состава.
     *
     * Отбор тот же — горизонт, подразделение, типы операций — но
     * исключаются строки, у которых уже есть привязка к ЗНП.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findPlainOperations(Section section) {
        return distributionService.groupKittingRows(plainRows(section));
    }

    /*
     * Строки комплектования раздела, под которые заказ-комплектующий
     * ещё не поднят.
     *
     * Один отбор на список и на счётчик меню: две границы одной
     * половины разошлись бы, и кладовщик шёл бы по цифре, которая не
     * сходится с тем, что он увидит на экране.
     */
    private List<OrderScheduleRow> plainRows(Section section) {

        List<Long> unitIds = unitIds(section);

        if (unitIds.isEmpty()) {
            return List.of();
        }

        List<OrderScheduleRow> rows = distributionService.findKittingRows(
                unitIds, KITTING_TYPE_CODES
        );

        if (rows.isEmpty()) {
            return List.of();
        }

        /*
         * Ключи «серийник + операция», под которые диспетчер уже
         * поднял заказ. Серийник строке графика даёт состав выпуска:
         * заказ планируется до выпуска, и строка номера не хранит.
         */
        Map<Long, ProductionOrderSerialProjection> serials =
                serialService.resolve(rows);

        Set<Long> serialIds = new LinkedHashSet<>();

        for (ProductionOrderSerialProjection serial : serials.values()) {
            if (serial != null && serial.getSerialId() != null) {
                serialIds.add(serial.getSerialId());
            }
        }

        Set<String> znpKeys = new LinkedHashSet<>();

        if (!serialIds.isEmpty()) {
            for (KittingZnpLinkView link
                    : serialProductionOrderRepository
                            .findZnpLinksBySerialIds(serialIds)) {

                znpKeys.add(pairKey(
                        link.getSerialId(), link.getOperationId()
                ));
            }
        }

        /*
         * Строки без привязки: у строки без серийника или без заказа
         * привязки быть не может, поэтому она остаётся в разделе —
         * это и есть работа, поднятая планированием, но не заказом.
         */
        List<OrderScheduleRow> plain = new ArrayList<>();

        for (OrderScheduleRow row : rows) {

            if (row.getOperation() == null) {
                continue;
            }

            ProductionOrderSerialProjection serial =
                    serials.get(row.getId());

            if (serial == null || serial.getSerialId() == null) {
                plain.add(row);
                continue;
            }

            if (!znpKeys.contains(
                    pairKey(serial.getSerialId(),
                            row.getOperation().getId()))) {
                plain.add(row);
            }
        }

        return plain;
    }

    /**
     * Комплектование ЗНП разрезом «изделие → его операции».
     *
     * Плоский список операций заставлял кладовщика собирать картину
     * изделия из строк разных групп: серийный номер повторялся в
     * каждой строке группы, а порядок сборки терялся. Здесь наоборот —
     * одна строка это одно изделие, а внутри раскрытия лежат операции
     * комплектования, под которые поднят заказ-комплектующий.
     *
     * В список попадают только операции с активным ЗНП: строка без
     * такого заказа складу не видна, потому что проводить её не под
     * что. Связь «серийник + операция → заказ» читается одним
     * пакетным запросом, зависимость маршрута — тем же правилом, что в
     * распределении, иначе два экрана разошлись бы во мнении о том,
     * можно ли проводить работу.
     *
     * Горизонт, отбор по подразделению и набор типов — общие с
     * {@link #findOperations}: два разреза одного раздела обязаны
     * показывать один и тот же объём работы.
     */
    @Transactional(readOnly = true)
    public List<KittingZnpSerialRow> findZnpProducts(Section section) {
        List<Long> unitIds = unitIds(section);

        if (unitIds.isEmpty()) {
            return List.of();
        }

        List<OrderScheduleRow> rows = distributionService.findKittingRows(
                unitIds, KITTING_TYPE_CODES
        );

        if (rows.isEmpty()) {
            return List.of();
        }

        /*
         * Серийник экземпляра: строка графика планирует изделие до
         * выпуска и номера не хранит, поэтому номер приходит из
         * состава выпуска по ключу «заказ + позиция + порядок».
         */
        Map<Long, ProductionOrderSerialProjection> serials =
                serialService.resolve(rows);

        if (serials.isEmpty()) {
            return List.of();
        }

        Map<Long, String> blockers =
                distributionService.blockerLabelsOf(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        Set<Long> serialIds = new LinkedHashSet<>();

        for (ProductionOrderSerialProjection serial : serials.values()) {
            if (serial != null && serial.getSerialId() != null) {
                serialIds.add(serial.getSerialId());
            }
        }

        if (serialIds.isEmpty()) {
            return List.of();
        }

        /*
         * Активные заказы-комплектующие: ключ пары «серийник +
         * операция», тот же, что в карточке выпуска.
         */
        Map<String, String> znpByPair = new HashMap<>();
        Set<String> znpNumbers = new LinkedHashSet<>();

        for (KittingZnpLinkView link
                : serialProductionOrderRepository
                        .findZnpLinksBySerialIds(serialIds)) {

            znpByPair.put(
                    pairKey(link.getSerialId(), link.getOperationId()),
                    link.getZnpNumber()
            );
            znpNumbers.add(link.getZnpNumber());
        }

        /*
         * Состав ЗНП — ячейки, привязанные к заказу по номеру.
         *
         * Один запрос: номер ЗНП известен из карточки операции, и
         * прямо по нему читаем ячейки матрицы, отфильтровав датчики.
         * Кладовщик видит, что уже вошло в заказ, до проведения.
         */
        Map<String, List<String>> cellsByZnp = new HashMap<>();

        if (!znpNumbers.isEmpty()) {
            for (ZnpCellLinkView cell
                    : serialProductionOrderRepository
                            .findCellsByZnpNumbers(znpNumbers)) {

                cellsByZnp
                        .computeIfAbsent(
                                cell.getZnpNumber(),
                                key -> new ArrayList<>()
                        )
                        .add(cell.getCellNumber());
            }
        }

        Map<Long, List<KittingZnpOperationRow>> bySerial =
                new LinkedHashMap<>();

        Map<Long, String> numbers = new HashMap<>();
        Map<Long, String> names = new HashMap<>();

        /*
         * Кэш поиска по обозначению: одна операция встречается в
         * списке десятки раз (по числу изделий), и ходить в
         * классификатор каждый раз незачем. Ключ — пустая строка для
         * операции без обозначения: её результат тоже кэшируется и
         * остаётся пустым списком.
         */
        Map<String, List<ProductClassifier>> kindsByToken =
                new HashMap<>();

        for (OrderScheduleRow row : rows) {
            ProductionOrderSerialProjection serial =
                    serials.get(row.getId());

            if (serial == null || serial.getSerialId() == null
                    || row.getOperation() == null) {
                continue;
            }

            String znpNumber = znpByPair.get(
                    pairKey(serial.getSerialId(),
                            row.getOperation().getId())
            );

            if (znpNumber == null) {
                continue;
            }

            boolean completed = row.getCompletedAt() != null;
            String blocker = blockers.get(row.getId());

            String opName = row.getOperation().getOperationName() == null
                    ? null
                    : row.getOperation().getOperationName().getName();

            String designation = extractCellDesignation(opName);

            /*
             * Кэш делит работу на две ветки: обозначение пустое —
             * операция ячейку не называет, и в классификатор нечего
             * искать; непустое — вид ячейки ограничен списком записей.
             */
            List<ProductClassifier> cellKinds = kindsByToken.computeIfAbsent(
                    designation,
                    this::findCellKinds
            );

            bySerial
                    .computeIfAbsent(
                            serial.getSerialId(),
                            key -> new ArrayList<>()
                    )
                    .add(new KittingZnpOperationRow(
                            row.getId(),
                            opName,
                            znpNumber,
                            row.getPlannedDate(),
                            completed,
                            completed
                                    ? row.getCompletedAt().format(RU_DATE_TIME)
                                    : null,
                            blocker != null,
                            blocker,
                            !completed && blocker == null,
                            cellsByZnp.getOrDefault(znpNumber, List.of()),
                            cellKinds.stream()
                                    .map(ProductClassifier::getDisplayName)
                                    .toList(),
                            designation
                    ));

            numbers.putIfAbsent(
                    serial.getSerialId(), serial.getFullNumber()
            );

            String displayName = serial.getDisplayName() != null
                    ? serial.getDisplayName()
                    : serialService.itemNameOf(row, itemNames);

            names.putIfAbsent(serial.getSerialId(), displayName);
        }

        List<KittingZnpSerialRow> result = new ArrayList<>();

        bySerial.forEach((serialId, operations) -> result.add(
                new KittingZnpSerialRow(
                        serialId,
                        numbers.get(serialId),
                        names.get(serialId),
                        operations
                )
        ));

        return result;
    }

    /**
     * Извлекает обозначение ячейки из наименования операции.
     *
     * Склад называет комплектовочную операцию по изделию, которое она
     * собирает: «Комплектование ЯПР20», «Получение ЯПР21». Обозначение
     * стоит последним словом, и именно оно отличает одну
     * комплектовочную операцию от другой. Правило держится на этом:
     * берётся последнее слово и принимается только то, в котором есть
     * цифра.
     *
     * Пустой ответ для «Комплектование», «Комплектование штанг» и
     * «Комплектование подсборок в групповой ЗНП»: наименование не
     * называет изделие, и ограничивать вид ячейки нечем — проверка
     * пропускается, а не отклоняет всё подряд.
     */
    static String extractCellDesignation(String operationName) {

        if (operationName == null || operationName.isBlank()) {
            return "";
        }

        String[] words = operationName.trim().split("\\s+");

        String last = words[words.length - 1];

        return CELL_DESIGNATION_PATTERN.matcher(last).matches()
                ? last
                : "";
    }

    /**
     * Ищет типы ячеек, которые допускает операция.
     *
     * По обозначению из наименования операции («ЯПР20») в
     * классификаторе берутся ячейки, содержащие его в наименовании:
     * «ЯПР20М» и «ЯПР20МД». Пустой список — обозначение в
     * классификаторе не найдено, и тогда вид ячейки не проверяется:
     * справочник заполняет технолог, и раздел не имеет права
     * блокировать работу из-за того, что в нём нет записи.
     *
     * Границу вхождения доводит здесь, а не запрос: «ЯПР2» —
     * подстрока «ЯПР20М», и без границы операция на ЯПР2 приняла бы
     * чужие ячейки. Наименование обязано содержать обозначение так,
     * чтобы за ним не шла цифра.
     */
    private List<ProductClassifier> findCellKinds(String token) {

        if (token == null || token.isBlank()) {
            return List.of();
        }

        return classifierRepository
                .findCellsByNameContaining(token)
                .stream()
                .filter(kind -> designationIn(kind.getDisplayName(), token))
                .toList();
    }

    /** Наименование содержит обозначение, не продолженное цифрой. */
    private static boolean designationIn(String name, String token) {

        if (name == null) {
            return false;
        }

        String lowerName = name.toLowerCase();
        String lowerToken = token.toLowerCase();

        for (int at = lowerName.indexOf(lowerToken);
                at >= 0;
                at = lowerName.indexOf(lowerToken, at + 1)) {

            int end = at + lowerToken.length();

            if (end >= lowerName.length()
                    || !Character.isDigit(lowerName.charAt(end))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Обозначение ячейки: слово, в котором есть цифра.
     *
     * Буква в начале обязательна — голая цифра или дата плана не
     * должны быть прочитаны как наименование изделия.
     */
    private static final Pattern CELL_DESIGNATION_PATTERN =
            Pattern.compile("[А-Яа-яA-Za-z][А-Яа-яA-Za-z0-9]*\\d+"
                    + "[А-Яа-яA-Za-z0-9-]*");

    /**
     * Проверка состава заказа перед проведением операции.
     *
     * Раздел обязан отвергать проведение тем же набором причин, что и
     * привязку: кладовщик вносит номер в поле, и если он обошёл
     * проверку (страница устарела, номер подставлен напрямую), то
     * проведение закрыло бы работу с чужим составом. Отличие двух
     * кнопок только в результате: «Внести» проверяет номер и
     * заполняет состав, «Провести» проверяет состав и закрывает
     * строку графика.
     *
     * Операция, которая ячейку не называет («Комплектование поплавков
     * для датчика»), проверок не проходит вовсе: у неё нет ни поля
     * ввода, ни состава, и требовать от неё внесённых номеров было бы
     * выдуманным условием.
     *
     * Операция, называющая ячейку, не проводится с пустым составом:
     * закрытая без номеров работа означает, что склад сдал заказ, ни
     * одной ячейки в него не положив, — а восстановить, что именно там
     * лежало, после этого нечем.
     *
     * Проверяются ВСЕ внесённые ячейки, а не одна: заказ на три
     * изделия собран из трёх номеров, и ошибочен любой из них.
     *
     * {@code null} — проверок нет или состав чист; текст — причина
     * отказа, которую раздел показывает окном.
     */
    @Transactional(readOnly = true)
    public String validateAttachedCells(Long rowId) {

        OrderScheduleRow row = scheduleRowRepository
                .findRowForAttachById(rowId)
                .orElse(null);

        if (row == null || row.getOperation() == null) {
            return null;
        }

        String designation =
                extractCellDesignation(operationNameOf(row.getOperation()));

        if (designation.isBlank()) {
            return null;
        }

        ProductionOrder order = findZnpOrderOfRow(row);

        if (order == null) {
            return null;
        }

        List<SerialProductionOrder> attached =
                serialProductionOrderRepository.findAttachedByOrderIds(
                        List.of(order.getId()));

        if (attached.isEmpty()) {
            return "Операция «" + operationNameOf(row.getOperation())
                    + "» ждёт ячеек «" + designation + "»: в заказ «"
                    + order.getNumber() + "» не вошло ни одного номера — "
                    + "сначала внесите состав";
        }

        List<ProductClassifier> kinds = findCellKinds(designation);

        if (kinds.isEmpty()) {
            return null;
        }

        Set<Long> allowedIds = new LinkedHashSet<>();

        for (ProductClassifier kind : kinds) {
            allowedIds.add(kind.getId());
        }

        for (SerialProductionOrder link : attached) {

            ProductSerial cell = link.getSerial();

            if (cell == null || cell.getProductType() != ProductType.CELL
                    || cell.getProductClassifier() == null) {
                continue;
            }

            if (!allowedIds.contains(cell.getProductClassifier().getId())) {
                return "Ячейка " + cell.getFullNumber() + " («"
                        + cell.getDisplayName() + "») не подходит к операции «"
                        + operationNameOf(row.getOperation())
                        + "»: склад принимает " + namesOf(kinds)
                        + " — проверьте состав заказа";
            }
        }

        return null;
    }

    /** Перечень наименований для сообщения об отказе. */
    private static String namesOf(List<ProductClassifier> kinds) {
        return String.join(", ",
                kinds.stream().map(ProductClassifier::getDisplayName).toList());
    }

    /** Наименование операции — подпись для сообщений и поиска обозначения. */
    private static String operationNameOf(Operation operation) {
        return operation.getOperationName() == null
                || operation.getOperationName().getName() == null
                ? ""
                : operation.getOperationName().getName();
    }

    /** Ключ пары «серийник + операция» — общий с карточкой выпуска. */
    private static String pairKey(Long serialId, Long operationId) {
        return Objects.toString(serialId, "0")
                + "|" + Objects.toString(operationId, "0");
    }

    /**
     * Привязка одной ячейки к заказу-комплектующему работы склада.
     *
     * До этого момента состав ЗНП собирал только раздел «Комплектование
     * подсборок», да и то для режима V72 — «изготовление ячеек из
     * подсборок». Обычное комплектование ЗНП ячейку принять было нечем:
     * кладовщик видел номер ЗНП в карточке операции и обязан был идти
     * искать другой экран, чтобы вписать в него изделие, — а того экрана
     * у его роли часто не было вовсе.
     *
     * Границы держатся те же, что у {@code SubassemblyKittingService
     * .attachCells}, потому что два раздела выписывают одну и ту же
     * матрицу (V20) и не имеют права расходиться в том, что в неё
     * впускать:
     *
     *   * изделие обязано быть ячейкой, а не датчиком и не системой;
     *   * оно обязано лежать на складе принятым и готовым, иначе в
     *     заказ попадёт позиция, которой склад ещё не располагает;
     *   * свободным номер считает отсутствие АКТИВНОЙ связи: снятый с
     *     работы заказ ячейку не держит, внесённый в живой заказ —
     *     держит, и второй заказ получил бы тот же номер;
     *   * потолок состава — заказанное количество, а его даёт число
     *     открытых экземпляров строки графика: заказ без плана
     *     количества не имеет.
     *
     * Проведение строки графика здесь не производится: привязка
     * заполняет состав, а закрывает работу кладовщик отдельно —
     * существующим {@code POST /warehouse/kitting/complete}, который
     * проверяет и право, и блокировку по маршруту.
     */
    @Transactional
    public KittingResult attachCellToZnp(Long rowId,
                                         String cellNumberText,
                                         AppUser executor) {

        OrderScheduleRow row = scheduleRowRepository
                .findRowForAttachById(rowId)
                .orElse(null);

        if (row == null || row.getOperation() == null) {
            return KittingResult.rejected(
                    "Строка плана не найдена — привязать ячейку не к чему"
            );
        }

        /*
         * Заказ-комплектующий ищется тем же путём, что и в списке
         * раздела, а не по основанию строки графика.
         *
         * Прежний код читал row.getProductionOrder(), и для строк,
         * спланированных планировщиком заказа покупателя, он пуст:
         * основание такой строки — заказ покупателя (V43), а
         * заказ-комплектующий живёт в матрице (V20) как связь
         * «серийник + операция». Кладовщик видел «Строка плана не
         * найдена — привязать ячейку не к чему» на живой строке,
         * у которой заказ есть и он виден в той же колонке таблицы.
         */
        ProductionOrder order = findZnpOrderOfRow(row);

        if (order == null) {
            return KittingResult.rejected(
                    "Под работу «" + operationNameOf(row.getOperation())
                            + "» нет заказ-комплектующего — вносить ячейку "
                            + "не в состав чего"
            );
        }

        if (!order.isActive()) {
            return KittingResult.rejected(
                    "Заказ «" + order.getNumber() + "» снят с работы — "
                            + "в него ничего не вносят"
            );
        }

        Operation operation = row.getOperation();

        /*
         * Привязывать разрешено только комплектовочную операцию: её
         * тип раздел читает теми же кодами, что и границу своего
         * списка, иначе ячейка могла бы войти в заказ цеховым этапом.
         */
        String typeCode = operation.getOperationType() == null
                || operation.getOperationType().getCode() == null
                ? null
                : operation.getOperationType().getCode().trim()
                        .toUpperCase();

        if (typeCode == null || !KITTING_TYPE_CODES.contains(typeCode)) {
            return KittingResult.rejected(
                    "Ячейку вносят только под комплектовочную операцию, "
                            + "а здесь работа «"
                            + (operation.getOperationName() == null
                                    ? "без наименования"
                                    : operation.getOperationName().getName())
                            + "»"
            );
        }

        String number = normalizeNumber(cellNumberText);

        if (number == null) {
            return KittingResult.rejected(
                    "Введите серийный номер ячейки — девять цифр"
            );
        }

        ProductSerial cell = findCellByFullNumber(number);

        if (cell == null) {
            return KittingResult.rejected(
                    "Номер " + number + " не найден в реестре изделий"
            );
        }

        if (cell.getProductType() != ProductType.CELL) {
            return KittingResult.rejected(
                    number + " — не ячейка: в состав заказ-комплектующего "
                            + "кладут ячейку, а не датчик и не изделие целиком"
            );
        }

        /*
         * Проверка вида ячейки по обозначению из наименования операции.
         *
         * «Комплектование ЯПР20» → обозначение «ЯПР20» → классификатор
         * возвращает «ЯПР20М» и «ЯПР20МД». Кладовщик вписывает номер —
         * сервис сверяет, что классификатор вписанной ячейки стоит в
         * этом списке. Пустой список (в классификаторе такой строки
         * нет) проверку пропускает: справочник ведёт технолог, и
         * раздел не вправе блокировать работу из-за незаполненной
         * записи, а неполное наименование операции — обычное дело для
         * работ, которые ячейку не называют («Комплектование
         * штанг», «Комплектование» без суффикса).
         */
        List<ProductClassifier> kinds = findCellKinds(
                extractCellDesignation(operationNameOf(operation))
        );

        if (!kinds.isEmpty()
                && cell.getProductClassifier() != null
                && kinds.stream().noneMatch(kind ->
                        kind.getId().equals(cell.getProductClassifier().getId()))) {

            return KittingResult.rejected(
                    "Ячейка " + number + " не подходит к операции «"
                            + operationNameOf(operation) + "»: склад принимает "
                            + kinds.stream()
                                    .map(ProductClassifier::getDisplayName)
                                    .toList()
                            + " — внесённый номер относится к «"
                            + cell.getDisplayName() + "»"
            );
        }

        if (!cell.isAccepted() || !cell.isCellReady()) {
            return KittingResult.rejected(
                    "Ячейка " + number + " ещё не лежит на складе "
                            + "готовой — принять её в заказ нельзя"
            );
        }

        if (serialProductionOrderRepository.existsActiveLinkBySerialId(
                cell.getId())) {
            return KittingResult.rejected(
                    "Ячейка " + number + " уже вошла в действующий заказ "
                            + "— со свободных полок она снята"
            );
        }

        /*
         * Потолок состава.
         *
         * Первым ставится лимит самого изделия: классификатор
         * заказчика говорит «в эту систему допускается одна ячейка
         * 5102» (classifier_inclusion.max_quantity, V10), и это
         * требование сильнее плана — два экземпляра плана означают два
         * прибора, а не две ячейки в одном.
         *
         * Без него границей служило число экземпляров операции
         * (см. countRowsOfProductionOrderAndOperation), и на серийных
         * ЗНП оно равно нулю: план заказанного изделия спланирован по
         * основанию «заказ покупателя» (V43), у его строк
         * productionOrder пуст, а запрос считал по заказу-
         * комплектующему. Ноль читается как «размер не задан», и
         * потолок не применялся вовсе — склад вносил вторую ячейку в
         * заказ, где по справочнику допущена одна.
         */
        long already = alreadyAttachedCells(order, cell);

        Long limit = cellLimitOf(order, cell);

        if (limit != null && already >= limit) {
            return KittingResult.rejected(
                    "В заказ «" + order.getNumber() + "» допущено " + limit
                            + " яч. «" + cell.getDisplayName() + "», уже вошло "
                            + already + " — сверх состава изделия номер не вносится"
            );
        }

        long required = plannedSizeOf(row, order, operation);

        if (required > 0 && already >= required) {
            return KittingResult.rejected(
                    "В заказ «" + order.getNumber() + "» уже вошло "
                            + already + " яч. — заказано " + required
                            + ", лишних номеров не принимаем"
            );
        }

        SerialProductionOrder link = new SerialProductionOrder();

        link.setSerial(cell);
        link.setProductionOrder(order);
        link.setOperation(operation);
        link.setCreatedBy(executor);
        link.setNote(LINK_NOTE);

        serialProductionOrderRepository.save(link);

        /*
         * ЖУРНАЛ: событие хранит состояние номера НА МОМЕНТ записи
         * (serialStatus в снимке), поэтому запись идёт после смены
         * статуса — иначе история показала бы ячейку всё ещё лежащей
         * на складе.
         */
        journal.recordInProduction(List.of(cell), order, executor);

        return KittingResult.accepted(
                "Ячейка " + number + " вошла в заказ «" + order.getNumber()
                        + "»"
        );
    }

    /**
     * Номер из поля ввода: только цифры, ровно девять.
     *
     * Поле принимает вставку из буфера и сканер, поэтому пробелы,
     * дефисы и перевод строки срезаются, а всё, что не стало девятью
     * цифрами, отклоняется — иначе кладовщик привязал бы ячейку по
     * неполному номеру и заметил бы это на сборке.
     */
    private static String normalizeNumber(String text) {

        if (text == null || text.isBlank()) {
            return null;
        }

        String digits = text.replaceAll("\\D", "");

        return digits.length() == FULL_NUMBER_LENGTH ? digits : null;
    }

    /**
     * Ячейка из реестра по полному номеру.
     *
     * Поиск арифметикой «код × 100000 + серия» — тот же, что в
     * глобальном поиске и в складском перечне: номер печатают именно
     * так, и разбор его на две части держит согласованность экрана с
     * реестром. Классификатор подтянут fetch join'ом: open-in-view
     * выключен, а наименование изделие печатает само.
     */
    private ProductSerial findCellByFullNumber(String fullNumber) {
        return serialRepository.findDetailsByFullNumbers(List.of(fullNumber))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Заказ-комплектующий, под который поднята данная строка графика.
     *
     * Ищется так же, как список раздела: строка графика находит свой
     * серийник по паре «заказ покупателя + позиция + индекс
     * экземпляра» (V43), а заказ-комплектующий — по паре «серийник +
     * операция» в матрице (V20). Основание строки для этого не годится:
     * у строк, спланированных планировщиком заказа покупателя,
     * productionOrder пуст, и прежний поиск по нему отклонял живую
     * работу с подписью «привязать ячейку не к чему».
     *
     * Среди найденных заказов предпочтён действующий: снятый с работы
     * заказ возвращается вызывающему коду отдельно, чтобы он подписал
     * отказ иначе, чем отсутствие заказа.
     */
    private ProductionOrder findZnpOrderOfRow(OrderScheduleRow row) {

        if (row.getOperation() == null) {
            return null;
        }

        /*
         * Строки группового ЗНП (V56) планируются самим заказом: у них
         * основание и есть заказ-комплектующий, и матрица к моменту
         * привязки ещё пуста — искать по серийнику нечего.
         */
        if (row.isProductionOrderBased()) {
            return row.getProductionOrder();
        }

        ProductionOrderSerialProjection serial =
                serialService.resolve(List.of(row)).get(row.getId());

        if (serial == null || serial.getSerialId() == null) {
            /*
             * Выпуск ещё не создан либо экземпляр не обеспечен номером:
             * у строки нет изделия, а значит, нет и пары, по которой
             * матрица связала бы её с заказом.
             */
            return row.getProductionOrder();
        }

        List<ProductionOrder> orders =
                serialProductionOrderRepository
                        .findZnpOrdersBySerialAndOperation(
                                serial.getSerialId(),
                                row.getOperation().getId()
                        );

        for (ProductionOrder candidate : orders) {
            if (candidate.isActive()) {
                return candidate;
            }
        }

        return orders.isEmpty() ? null : orders.get(0);
    }

    /**
     * Сколько ячеек такого же изделия уже вошло в заказ.
     *
     * Считается по классификатору, а не по всем строкам матрицы: в
     * той же матрице стоит изделие-носитель, и общий счётчик
     * приписывал бы ему роль ячейки («вошло 2» при одной внесённой).
     */
    private long alreadyAttachedCells(ProductionOrder order, ProductSerial cell) {

        Long classifierId = cell.getProductClassifier() == null
                ? null
                : cell.getProductClassifier().getId();

        return classifierId == null
                ? serialProductionOrderRepository
                        .countByProductionOrder_Id(order.getId())
                : serialProductionOrderRepository.countCellsOfClassifierInOrder(
                        order.getId(), classifierId);
    }

    /**
     * Сколько ячеек этого изделия допускает изделие-носитель заказа.
     *
     * Границу задаёт состав изделия из справочника
     * (classifier_inclusion, V10): «в датчик 1192 допускается одна
     * ячейка ЯПР20М». Она сильнее плана, потому что экземпляры плана
     * считают приборы, а не детали одного прибора.
     *
     * Носитель ищется составом заказ-комплектующего: в матрицу наряду с
     * ячейками входит выпускаемое изделие, и только оно имеет позицию
     * заказа покупателя. У группового ЗНП выпуска нет — выдача пустая,
     * и потолок по составу изделия к нему неприменим.
     *
     * Из нескольких записей справочника берётся наименьший предел:
     * заказ обязан уложиться в самый тесный из допущенных.
     *
     * @return null, если изделие заказа лимита не задаёт
     */
    private Long cellLimitOf(ProductionOrder order, ProductSerial cell) {

        Long cellClassifierId = cell.getProductClassifier() == null
                ? null
                : cell.getProductClassifier().getId();

        if (cellClassifierId == null || order.getId() == null) {
            return null;
        }

        Long limit = null;

        for (Long carrierId
                : itemRepository.findCarrierClassifierIdsOfZnp(order.getId())) {

            for (ClassifierInclusion inclusion
                    : inclusionRepository.findByOwnerAndTarget(
                            carrierId, cellClassifierId)) {

                BigDecimal max = inclusion.getMaxQuantity();

                if (max == null || max.signum() <= 0) {
                    continue;
                }

                /* Дробный предел округляется вниз: полупустая ячейка не бывает. */
                long allowed = max.setScale(0, RoundingMode.FLOOR).longValue();

                if (allowed > 0 && (limit == null || allowed < limit)) {
                    limit = allowed;
                }
            }
        }

        return limit;
    }

    /**
     * Размер комплектования по плану — сколько экземпляров операции
     * заказано.
     *
     * Считается по тому же основанию, которым спланирована строка:
     * групповой ЗНП планирует работу сам (V56), а работу серийного
     * заказ-комплектующего планируют по заказу покупателя (V43), и
     * подсчёт по заказу-комплектующему там даёт ноль.
     *
     * @return 0, если планом размер не задан — потолок тогда не
     *         применяется
     */
    private long plannedSizeOf(OrderScheduleRow row,
                               ProductionOrder order,
                               Operation operation) {

        if (operation.getId() == null) {
            return 0L;
        }

        if (row.isProductionOrderBased()) {
            return scheduleRowRepository
                    .countRowsOfProductionOrderAndOperation(
                            order.getId(), operation.getId());
        }

        if (row.getCustomerOrder() == null
                || row.getCustomerOrder().getId() == null
                || row.getItemPosition() == null) {
            return 0L;
        }

        return scheduleRowRepository
                .countRowsOfCustomerOrderItemAndOperation(
                        row.getCustomerOrder().getId(),
                        row.getItemPosition(),
                        operation.getId());
    }

    /**
     * Незакрытые операции комплектования раздела — для счётчика меню.
     */
    @Transactional(readOnly = true)
    public long countOperations(Section section) {
        List<Long> unitIds = unitIds(section);

        return unitIds.isEmpty()
                ? 0L
                : distributionService.countKittingOperations(
                        unitIds, KITTING_TYPE_CODES
                );
    }

    /**
     * Незакрытые операции комплектования без заказ-комплектующего —
     * для счётчика меню.
     *
     * Считается запросом, а не размером списка: меню рисуется на
     * каждой странице модуля, а подбор серийников и экземпляров
     * ради одной цифры стоил бы десятков запросов. Граница — та же,
     * что у {@link #findPlainOperations}: строки, под которые
     * диспетчер ещё не поднял заказ-комплектующий.
     */
    @Transactional(readOnly = true)
    public long countPlainOperations(Section section) {
        List<OrderScheduleRow> plain = plainRows(section);

        if (plain.isEmpty()) {
            return 0L;
        }

        /*
         * Собираю серийники строк и читаю ЗНП-связи — та же
         * логика, что в списке, но без группировки: нужно только
         * число строк, которые пройдут отсев.
         */
        Map<Long, ProductionOrderSerialProjection> serials =
                serialService.resolve(plain);

        Set<Long> serialIds = new LinkedHashSet<>();

        for (ProductionOrderSerialProjection serial : serials.values()) {
            if (serial != null && serial.getSerialId() != null) {
                serialIds.add(serial.getSerialId());
            }
        }

        Set<String> znpKeys = new LinkedHashSet<>();

        if (!serialIds.isEmpty()) {
            for (KittingZnpLinkView link
                    : serialProductionOrderRepository
                            .findZnpLinksBySerialIds(serialIds)) {

                znpKeys.add(pairKey(
                        link.getSerialId(), link.getOperationId()
                ));
            }
        }

        long count = 0L;

        for (OrderScheduleRow row : plain) {
            if (row.getOperation() == null) {
                continue;
            }

            ProductionOrderSerialProjection serial =
                    serials.get(row.getId());

            if (serial == null || serial.getSerialId() == null) {
                count++;
                continue;
            }

            if (!znpKeys.contains(
                    pairKey(serial.getSerialId(),
                            row.getOperation().getId()))) {
                count++;
            }
        }

        return count;
    }

    /** Раздел по параметру адреса; неизвестное значение — ЗНП. */
    public static Section ofNullable(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase();

            for (Section section : Section.values()) {
                if (section.param().equals(normalized)) {
                    return section;
                }
            }
        }

        return Section.ZNP;
    }

    /*
     * Подразделение склада по коду справочника.
     *
     * Список, а не один идентификатор, — потому что выборка графика
     * отбирает работы по набору подразделений: если в справочнике
     * заведётся второе подразделение того же склада, раздел подхватит
     * его без правки кода.
     */
    private List<Long> unitIds(Section section) {
        return organizationUnitRepository
                .findByCodeIgnoreCase(section.getUnitCode())
                .map(unit -> List.of(unit.getId()))
                .orElse(List.of());
    }

    /**
     * Итог привязки ячейки: проведено или отвергнуто и почему.
     *
     * Форма {@code SubassemblyKittingService.KittingResult} здесь не
     * переиспользуется намеренно: та несёт размер внесённого состава,
     * а разделу нужен только ответ кладовщику — одна ячейка за раз, и
     * число в сообщении ничего бы не добавило.
     */
    public record KittingResult(boolean completed, String message) {

        public static KittingResult accepted(String message) {
            return new KittingResult(true, message);
        }

        public static KittingResult rejected(String message) {
            return new KittingResult(false, message);
        }
    }
}

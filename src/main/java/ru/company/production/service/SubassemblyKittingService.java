package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.SubassemblyKittingCellRow;
import ru.company.production.dto.SubassemblyKittingZnpRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Комплектование подсборок в ЗНП: раздел склада полуфабрикатов.
 *
 * Раздел собирает ЗНП, у которых склад принимает подсборки. Операция
 * приёмки ищется в ДВУХ местах:
 *
 *   * заказ-комплектующий, вложенный в выпуск — операция на самом
 *       заказе, тип «Приёмка на склад» (RECEIVING);
 *   * групповой или ремонтный ЗНП — операция в техпроцессе, тип
 *       «Комплектование подсборок» (KITTING_SUBASSEMBLY).
 *
 * Обе операции принадлежат подразделению «Склад полуфабрикатов».
 *
 * Кладовщик открывает заказ, видит перечень ячеек, принятых на склад,
 * и вносит нужные номера. Внесённой ячейку делает строка матрицы
 * комплектования {@link SerialProductionOrder} (V20) — та же связь,
 * которой пользуются карточка изделия и приёмка состава, поэтому
 * раздел не заводит параллельный учёт состава, а пишет в общий.
 *
 * Проводить можно только то, что реально лежит на складе: право
 * номера участвовать в комплектовании даёт отметка приёмки
 * ({@code accepted_at}), которую ставит {@link BvkAcceptanceService}.
 */
@Service
@RequiredArgsConstructor
public class SubassemblyKittingService {

    /** Типы операций, которыми склад принимает подсборки. */
    private static final List<String> KITTING_TYPE_CODES = List.of(
            "RECEIVING",
            "KITTING_SUBASSEMBLY"
    );

    /** Подразделение-владелец операций приёмки (миграция V2). */
    private static final String WAREHOUSE_UNIT_CODE =
            "SEMI_FINISHED_PRODUCT_WAREHOUSE";

    /** Длина полного номера: 4 цифры кода + 5 цифры серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Номеров в сообщении об ошибке: длинный список не читаем. */
    private static final int MESSAGE_NUMBER_LIMIT = 12;

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductSerialRepository serialRepository;
    private final SerialProductionOrderRepository linkRepository;
    private final TechProcessOperationRepository techProcessOpRepository;
    private final SerialMovementJournalService journal;
    private final InputControlService inputControlService;

    /**
     * ЗНП с операцией приёмки на склад полуфабрикатов — список раздела.
     *
     * Принятые на склад ячейки читаются ОДНИМ запросом на всю страницу:
     * перечень общий для всех заказов, и запрос на каждую строку списка
     * дал бы N+1 на экране, где строк десятки.
     *
     * Попадание ячейки в заказ определяется матрицей комплектования,
     * поэтому строка знает и весь складской перечень, и то, что уже
     * внесено в этот ЗНП, — кладовщик видит остаток, а не гадает.
     */
    @Transactional(readOnly = true)
    public List<SubassemblyKittingZnpRow> findOrders() {

        List<ProductionOrder> orders =
                productionOrderRepository.findSubassemblyKittingOrders(
                        ProductionOrderStatus.IN_WORK,
                        KITTING_TYPE_CODES,
                        WAREHOUSE_UNIT_CODE
                );

        if (orders.isEmpty()) {
            return List.of();
        }

        List<SubassemblyKittingCellRow> stockRows = stockRows();

        List<SubassemblyKittingZnpRow> result = new ArrayList<>();

        /*
         * Операции маршрутов читаются одним запросом на всю страницу:
         * у групповых ЗНП приёмка живёт в техпроцессе, и запрос на
         * каждый заказ дал бы N+1 на экране с десятками строк.
         */
        Map<Long, Operation> routeOperations =
                routeOperationsByProcess(orders);

        for (ProductionOrder order : orders) {

            Set<String> taken = takenNumbers(order.getId());

            result.add(new SubassemblyKittingZnpRow(
                    order.getId(),
                    order.getNumber(),
                    order.getZnpKindDisplayName(),
                    order.getProductTypeName(),
                    order.getTechProcessCode(),
                    operationName(order, routeOperations),
                    order.getFormattedOrderDate(),
                    order.getFormattedDueDate(),
                    order.getStatusDisplayName(),
                    order.getStatusBadgeClass(),
                    order.getNote(),
                    order.getQuantity(),
                    orderCells(order, stockRows, taken)
            ));
        }

        return result;
    }

    /**
     * Экран внесения ячеек: заказ, его реквизиты и складской перечень.
     *
     * Заказ читается детальным запросом: карточка печатает номер
     * родителя, вид заказа и наименование операции, а связи ленивые
     * ({@code open-in-view} выключен).
     */
    @Transactional(readOnly = true)
    public SubassemblyKittingView loadView(Long orderId) {

        ProductionOrder order = requireKittingOrder(orderId);

        Set<String> taken = takenNumbers(orderId);

        List<SubassemblyKittingCellRow> cells =
                orderCells(order, stockRows(), taken);

        return new SubassemblyKittingView(
                order,
                cells,
                quarantineCells(order),
                operationName(order, routeOperationsByProcess(List.of(order)))
        );
    }

    /**
     * Ячейки и подсборки изолятора брака по изделию заказа.
     *
     * Блок справочный: ячейка изолятора на склад не принята, и внести
     * её в заказ нельзя — кладовщик видит её, чтобы понимать, где
     * недостающие подсборки, а не искать их вслепую.
     *
     * Отбор по классификатору заказа: перечень изолятора велик, и
     * показывать в нём чужие изделия значило бы заставить кладовщика
     * разбирать его вручную.
     */
    private List<QuarantineCellView> quarantineCells(ProductionOrder order) {

        Long classifierId = order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();

        if (classifierId == null) {
            return List.of();
        }

        return inputControlService.findQuarantineCells(
                new QuarantineFilter(null, classifierId, null)
        );
    }

    /**
     * Список ячеек, принятых на склад полуфабрикатов.
     *
     * Отдельный метод, чтобы не дублировать отобра в {@code findOrders}
     * и {@code loadView}.
     */
    private List<SubassemblyKittingCellRow> stockRows() {

        List<ProductSerial> stock = serialRepository
                .findAcceptedOnWarehouse();

        return stock.stream()
                .map(serial -> new SubassemblyKittingCellRow(
                        serial.getId(),
                        serial.getFullNumber(),
                        serial.getDisplayName(),
                        serial.getProductClassifier() == null
                                ? null
                                : String.valueOf(
                                        serial.getProductClassifier().getCode()
                                ),
                        serial.getProductClassifier() == null
                                ? null
                                : serial.getProductClassifier().getId()
                ))
                .toList();
    }

    /**
     * Номера ячеек, уже внесённых в заказ.
     */
    private Set<String> takenNumbers(Long orderId) {
        return linkRepository
                .findByProductionOrderId(orderId).stream()
                .filter(link -> link.getSerial() != null)
                .map(link -> link.getSerial().getFullNumber())
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new
                ));
    }

    /**
     * Складской остаток, допустимый для заказа: принятые складом ячейки
     * того же изделия, что заказано.
     *
     * Тот же отбор, что печатает экран: проверка проведения и перечень
     * на экране обязаны совпадать, иначе кладовщику показали бы ячейку,
     * которую провести не дали бы.
     */
    private List<ProductSerial> allowedStock(ProductionOrder order) {

        Long classifierId = order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();

        if (classifierId == null) {
            return serialRepository.findAcceptedOnWarehouse();
        }

        return serialRepository.findAcceptedOnWarehouse().stream()
                .filter(serial -> serial.getProductClassifier() != null
                        && classifierId.equals(
                                serial.getProductClassifier().getId()))
                .toList();
    }

    /**
     * Перечень ячеек для заказа: склад, отфильтрованный по изделию
     * заказа.
     *
     * Кладовщик должен видеть только то, что входит в заказ, — иначе
     * среди «подсборок» он может пропустить чужие ячейки.
     */
    private List<SubassemblyKittingCellRow> orderCells(
            ProductionOrder order,
            List<SubassemblyKittingCellRow> allStock,
            Set<String> taken) {

        Long classifierId = order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();

        return allStock.stream()
                .filter(row -> classifierId == null
                        || classifierId.equals(row.classifierId()))
                .map(row -> row.mark(taken.contains(row.fullNumber())))
                .toList();
    }

    /** Заказов, ожидающих комплектования: счётчик пункта меню. */
    @Transactional(readOnly = true)
    public long countPending() {
        return productionOrderRepository.countSubassemblyKittingOrders(
                ProductionOrderStatus.IN_WORK,
                KITTING_TYPE_CODES,
                WAREHOUSE_UNIT_CODE
        );
    }

    /**
     * Вносит ячейки в заказ-комплектующий.
     *
     * Проводится список номеров, а не отметка на заказе: кладовщик
     * обязан видеть, какие именно подсборки ушли в ЗНП, — иначе спор
     * о составе остался бы неразрешимым.
     *
     * Отказ даёт весь перечень проблемных номеров, а не первый:
     * половину списка править по одному сообщению значит переоткрывать
     * экран столько раз, сколько ошибок допустил человек.
     *
     * Already внесённые номера молча пропускаются — повторный клик не
     * должен ломать уже проведённое.
     */
    @Transactional
    public KittingResult attachCells(Long orderId,
                                     String cellNumbersText,
                                     AppUser executor) {

        ProductionOrder order = requireKittingOrder(orderId);

        List<String> chosen = parseNumbers(cellNumbersText);

        if (chosen.isEmpty()) {
            return KittingResult.rejected(
                    "Внесите номера ячеек, которые берёте в заказ «"
                            + order.getNumber() + "»"
            );
        }

        /*
         * Складской перечень — единственная граница допустимого: номера
         * нет на складе, если он не принят (accepted_at пуст) или его
         * нет в реестре вовсе.
         *
         * Границу сужают изделием заказа: чужую ячейку кладовщик
         * провести не может, и молча принять её значило бы испортить
         * состав заказа.
         */
        List<ProductSerial> stock = allowedStock(order);

        /*
         * Реальное количество ячеек, уже заведённых в заказ.
         * Считаем по матрице комплектования, а не по текущему вводу.
         */
        long already = linkRepository
                .countByProductionOrder_Id(orderId);

        List<String> missing = chosen.stream()
                .filter(number -> stock.stream()
                        .noneMatch(s -> number.equals(s.getFullNumber())))
                .toList();

        if (!missing.isEmpty()) {
            return KittingResult.rejected(
                    "Этих ячеек нет на складе полуфабрикатов — принять в ЗНП "
                            + "можно только принятые складом изделия заказа: "
                            + join(missing)
            );
        }

        Operation operation = findKittingOperation(order);

        if (operation == null) {
            throw new IllegalStateException(
                    "В заказе «" + order.getNumber()
                            + "» не задана операция приёмки на склад"
            );
        }

        /*
         * Состав собирают ДО записи в базу: заказанное количество —
         * граница, и проверить её нужно на всём перечне сразу.
         * Проводить по одной ячейке до первого отказа значило бы
         * оставить заказ половину списка принявшим, а половину — нет.
         */
        List<ProductSerial> attached = new ArrayList<>();

        for (String number : chosen) {

            ProductSerial serial = stock.stream()
                    .filter(s -> number.equals(s.getFullNumber()))
                    .findFirst()
                    .orElse(null);

            if (serial == null) {
                continue;
            }

            if (linkRepository.existsBySerial_IdAndProductionOrder_Id(
                    serial.getId(), orderId)) {
                continue;
            }

            attached.add(serial);
        }

        /*
         * Если ничего нового не собрано — либо все ячейки дубликаты,
         * либо нет на складе.
         */
        if (attached.isEmpty()) {
            return KittingResult.rejected(
                    already > 0
                            ? "Все внесённые ячейки уже входят в заказ «"
                            + order.getNumber() + "»"
                            : "Внесите номера ячеек, которые берёте в заказ «"
                            + order.getNumber() + "»"
            );
        }

        /*
         * Заказанное количество — потолок состава.
         *
         * Границу держит сервис, а не интерфейс: сканер добавляет
         * номер повторно, а поле принял и вставку из буфера целиком, и
         * без отказа заказ собрал бы шесть подсборок при заказанных
         * пяти — кладовщик заметил бы это уже на сборке.
         *
         * Количество задают только групповым и ремонтным видам; у
         * заказа на комплектование состава нет, и ограничивать нечем.
         */
        Long required = requiredQuantity(order);

        if (required != null) {

            long total = already + attached.size();

            if (total > required) {
                return KittingResult.rejected(
                        "В заказ «" + order.getNumber() + "» заказано "
                                + required + " яч., а вносится " + total
                                + " (уже вошло " + already + "). Уберите лишние номера: "
                                + join(attached.stream()
                                .skip(Math.max(0, required - already))
                                .map(ProductSerial::getFullNumber)
                                .toList())
                );
            }
        }

        for (ProductSerial serial : attached) {

            SerialProductionOrder link = new SerialProductionOrder();

            link.setSerial(serial);
            link.setProductionOrder(order);
            link.setOperation(operation);
            link.setCreatedBy(executor);
            link.setNote("Комплектование подсборок на складе");

            linkRepository.save(link);
        }

        journal.recordInProduction(attached, order, executor);

        return KittingResult.accepted(
                "Внесено в заказ «" + order.getNumber() + "»: "
                        + attached.size() + " яч."
                        + (already > 0
                                ? "; уже вошло ранее " + already + " яч."
                                : "")
        );
    }

    /*
     * Заказанное количество как потолок состава.
     *
     * Дробное количество округляют вверх: заказывают штуки, и
     * «5.000» и «4.500» означают, что в заказ войдёт пять ячеек.
     */
    private static Long requiredQuantity(ProductionOrder order) {

        BigDecimal quantity = order.getQuantity();

        return quantity == null
                ? null
                : quantity.setScale(0, RoundingMode.CEILING).longValue();
    }

    /**
     * Заказ обязан быть комплектующим: выпуск подсборки на склад не
     * принимает, а групповой и ремонтный ЗНП самостоятельны.
     */
    private ProductionOrder requireKittingOrder(Long orderId) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Заказ на производство не найден"
                ));

        if (!order.isComponent()) {
            throw new IllegalStateException(
                    "Комплектование подсборок проходит по заказу-"
                            + "комплектующему, а заказ «" + order.getNumber()
                            + "» им не является"
            );
        }

        return order;
    }

    /**
     * Наименование операции, по которой склад принимает подсборки.
     *
     * Для заказа-комплектующего — операция самого заказа; для группового
     * ЗНП — операция из техпроцесса с подходящим типом и подразделением.
     *
     * Справочник операций маршрута передан одним мапом — N+1 не строим.
     */
    private String operationName(ProductionOrder order,
                                 Map<Long, Operation> routeOperations) {

        Operation operation = order.getProductionOperation();

        if (operation != null
                && operation.getOperationName() != null
                && isWarehouseOperation(operation)) {
            return operation.getOperationName().getName();
        }

        if (order.getTechProcess() != null) {
            Operation routeOp = routeOperations.get(
                    order.getTechProcess().getId());
            if (routeOp != null
                    && routeOp.getOperationName() != null) {
                return routeOp.getOperationName().getName();
            }
        }

        return "—";
    }

    /**
     * Операции маршрутов для отбора комплектования — один запрос.
     */
    private Map<Long, Operation> routeOperationsByProcess(
            List<ProductionOrder> orders) {

        List<Long> processIds = orders.stream()
                .map(ProductionOrder::getTechProcess)
                .filter(Objects::nonNull)
                .map(TechProcess::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (processIds.isEmpty()) {
            return Map.of();
        }

        return techProcessOpRepository
                .findUnitKittingNodesForProcesses(
                        processIds,
                        "KITTING_SUBASSEMBLY",
                        WAREHOUSE_UNIT_CODE
                ).stream()
                .collect(LinkedHashMap::new,
                        (map, node) -> {
                            Long pid = node.getTechProcess() != null
                                    ? node.getTechProcess().getId() : null;
                            if (pid != null) {
                                map.putIfAbsent(pid, node.getOperation());
                            }
                        },
                        Map::putAll
                );
    }

    /**
     * Операция, по которой склад принимает подсборки: операция заказа
     * или операция техпроцесса.
     */
    private Operation findKittingOperation(ProductionOrder order) {

        Operation operation = order.getProductionOperation();

        if (operation != null && isWarehouseOperation(operation)) {
            return operation;
        }

        if (order.getTechProcess() != null) {
            List<TechProcessOperation> nodes =
                    techProcessOpRepository.findUnitKittingNodesForProcesses(
                            List.of(order.getTechProcess().getId()),
                            "KITTING_SUBASSEMBLY",
                            WAREHOUSE_UNIT_CODE
                    );

            if (!nodes.isEmpty()) {
                return nodes.get(0).getOperation();
            }
        }

        return null;
    }

    /**
     * Подразделение операции — склад полуфабрикатов.
     */
    private static boolean isWarehouseOperation(Operation operation) {
        if (operation.getOrganizationUnit() == null) {
            return false;
        }
        return WAREHOUSE_UNIT_CODE.equals(
                operation.getOrganizationUnit().getCode());
    }

    /**
     * Разбор поля с номерами.
     *
     * Поле заполняют и с клавиатуры, и сканером: строки разделяются
     * переводом, запятой или точкой с запятой, сканер добавляет
     * префиксы и пробелы. Оставляют только строки годной длины полного
     * номера — остальное отсекается здесь и всплывает как «ячейки нет
     * на складе».
     */
    private static List<String> parseNumbers(String text) {

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> numbers = new LinkedHashSet<>();

        for (String line : text.split("[,;\\r\\n]+")) {

            String digits = line.replaceAll("\\D", "");

            if (digits.length() == FULL_NUMBER_LENGTH) {
                numbers.add(digits);
            }
        }

        return List.copyOf(numbers);
    }

    /**
     * Перечень номеров в сообщении: длинный список обрезают, иначе
     * плашка растянулась бы на весь экран.
     */
    private static String join(List<String> numbers) {
        return numbers.size() <= MESSAGE_NUMBER_LIMIT
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, MESSAGE_NUMBER_LIMIT))
                + " …";
    }

    /**
     * Экран комплектования: заказ, складской перечень и подпись
     * операции приёмки.
     */
    public record SubassemblyKittingView(
            ProductionOrder order,
            List<SubassemblyKittingCellRow> cells,
            List<QuarantineCellView> quarantine,
            String operationName
    ) {

        /** Ячеек уже внесено в заказ. */
        public long takenCount() {
            return cells == null
                    ? 0L
                    : cells.stream()
                    .filter(SubassemblyKittingCellRow::taken)
                    .count();
        }

        /** Ячеек доступно на складе всего. */
        public long availableCount() {
            return cells == null ? 0L : cells.size();
        }

        /*
         * Сколько ячеек заказано.
         *
         * Тот же расчёт, что у SubassemblyKittingZnpRow#requiredCount():
         * групповой и ремонтный заказ задан количеством, и считать его
         * укомплектованным нужно по заказанному, а не по складскому
         * остатку. У заказа на комплектование количества нет — там
         * знаменатель остаётся складской перечень.
         */
        public long requiredCount() {
            BigDecimal quantity = order == null
                    ? null
                    : order.getQuantity();

            return quantity == null
                    ? availableCount()
                    : quantity.setScale(0, RoundingMode.CEILING).longValue();
        }

        /** Вносить нечего: заказанное количество уже внесено. */
        public boolean nothingToAdd() {
            return takenCount() >= requiredCount();
        }
    }

    /**
     * Итог проведения: что провести удалось, а что отвергнуто и почему.
     *
     * Форма {@link ru.company.production.dto.InputControlResult} здесь
     * не переиспользуется: её смысл — отметка контроля на ячейке, а
     * не размер внесённого состава.
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
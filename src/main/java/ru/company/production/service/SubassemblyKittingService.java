package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.SubassemblyKittingCellRow;
import ru.company.production.dto.SubassemblyKittingZnpRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final CellReceivingBatchRepository batchRepository;
    private final ProductSerialRepository serialRepository;
    private final SerialProductionOrderRepository linkRepository;
    private final TechProcessOperationRepository techProcessOpRepository;
    private final SerialMovementJournalService journal;
    private final InputControlService inputControlService;
    private final ProductSerialOperationRepository serialOperationRepository;

    /*
     * План заказа (V56): строки графика второй ветви — то, что читает
     * цех. Склад обязан проводить свою строку сам, иначе «Комплектование
     * подсборок» осталось бы незакрытым в плане и следующая по маршруту
     * операция («Монтаж подсборок») не разблокировалась бы, даже когда
     * все ячейки внесены.
     */
    private final OrderScheduleRowRepository scheduleRowRepository;

    /**
     * ЗНП с операцией приёмки на склад полуфабрикатов — список раздела.
     *
     * Оба перечня читаются пакетами на всю страницу: свободный остаток
     * общий для всех заказов, а внесённый состав — свой у каждого
     * заказа, но и он выбирается одним запросом по идентификаторам
     * страницы. Выборка на каждую строку дала бы два N+1 на экране, где
     * строк десятки.
     *
     * Строка знает и свободный склад, и свой состав: кладовщик видит,
     * что уже оформлено в этот ЗНП и что ещё можно взять.
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

        /*
         * Оба свободных перечня читаются лениво и по одному разу:
         * принятый складской остаток нужен обычным заказам, непринятые
         * подсборки — заказам режима V72. Запрашивать оба на каждую
         * загрузку списка значило бы всегда платить одним лишним
         * обращением к базе, хотя заказов второго рода обычно нет.
         */
        List<SubassemblyKittingCellRow> stockRows = null;
        List<SubassemblyKittingCellRow> unacceptedRows = null;

        /*
         * Операции маршрутов читаются одним запросом на всю страницу:
         * у групповых ЗНП приёмка живёт в техпроцессе, и запрос на
         * каждый заказ дал бы N+1 на экране с десятками строк.
         */
        Map<Long, Operation> routeOperations =
                routeOperationsByProcess(orders);

        List<Long> orderIds = orders.stream()
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, List<SubassemblyKittingCellRow>> attachedByOrder =
            attachedRowsByOrder(orderIds);

        /*
         * Номера изменений — одним запросом на страницу: изделие
         * печатается у каждой строки, а запрос на заказ дал бы N+1.
         */
        Map<Long, String> changeNumbers = latestChangeNumbers(orders);

        List<SubassemblyKittingZnpRow> result = new ArrayList<>();

        for (ProductionOrder order : orders) {

            List<SubassemblyKittingCellRow> free;

            if (order.isSubassemblyKitting()) {
                if (unacceptedRows == null) {
                    unacceptedRows = unacceptedStockRows();
                }
                free = freeCells(order, unacceptedRows);
            } else {
                if (stockRows == null) {
                    stockRows = stockRows();
                }
                free = freeCells(order, stockRows);
            }

            result.add(new SubassemblyKittingZnpRow(
                    order.getId(),
                    order.getNumber(),
                    order.getZnpKindDisplayName(),
                    order.getStockProductTypeName(),
                    order.getClassifierLabelWithChange(
                            changeNumbers.get(order.getClassifierId())
                    ),
                    order.getKittingTypeDisplayName(),
                    order.getTechProcessCode(),
                    operationName(order, routeOperations),
                    order.getFormattedOrderDate(),
                    order.getFormattedDueDate(),
                    order.getStatusDisplayName(),
                    order.getStatusBadgeClass(),
                    order.getNote(),
                    order.getQuantity(),
                    free,
                    attachedByOrder.getOrDefault(order.getId(), List.of())
            ));
        }

        return result;
    }

    /**
     * Экран внесения ячеек: заказ, его реквизиты, свободный склад и
     * уже внесённый состав.
     *
     * Заказ читается детальным запросом: карточка печатает номер
     * родителя, вид заказа и наименование операции, а связи ленивые
     * ({@code open-in-view} выключен).
     *
     * Для заказа с {@code kittingType=SUBASSEMBLY} (V72) свободный
     * перечень — непринятые подсборки из {@code findUnacceptedCells()},
     * а не принятые ячейки из {@code findFreeOnWarehouse()}.
     */
    @Transactional(readOnly = true)
    public SubassemblyKittingView loadView(Long orderId) {

        ProductionOrder order = requireKittingOrder(orderId);

        /*
         * Режим V72: свободный перечень — непринятые подсборки, а не
         * принятые ячейки. Отбор по изделию заказа общий для обоих
         * перечней, поэтому тот же freeCells.
         */
        List<SubassemblyKittingCellRow> free =
                order.isSubassemblyKitting()
                        ? freeCells(order, unacceptedStockRows())
                        : freeCells(order, stockRows());

        return new SubassemblyKittingView(
                order,
                free,
                attachedRows(orderId),
                quarantineCells(order),
                operationName(order, routeOperationsByProcess(List.of(order))),
                latestChangeNumber(order)
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
     * Свободный складской остаток: принятые складом ячейки, ещё не
     * оформленные ни в один заказ.
     *
     * Отдельный метод, чтобы не дублировать отобра в {@code findOrders}
     * и {@code loadView}.
     */
    private List<SubassemblyKittingCellRow> stockRows() {

        List<ProductSerial> stock = serialRepository.findFreeOnWarehouse();

        return stock.stream()
                .map(SubassemblyKittingService::toCellRow)
                .toList();
    }

    /**
     * Свободный перечень режима V72: подсборки, дошедшие до склада, но
     * ещё не принятые.
     *
     * Приёмка такого заказа и есть перевод подсборки в ячейку, поэтому
     * кладовщику показывают то, что ещё не принято, — принятая ячейка
     * в этом перечне уже не числится.
     */
    private List<SubassemblyKittingCellRow> unacceptedStockRows() {

        return serialRepository.findUnacceptedCells().stream()
                .map(SubassemblyKittingService::toCellRow)
                .toList();
    }

    /**
     * Ячейки, уже оформленные в заказ.
     *
     * Читаются матрицей комплектования (V20), а не складским перечнем:
     * проведённая в ЗНП подсборка со свободных полок снята и в остатке
     * не показывается. Кладовщик обязан видеть внесённый состав —
     * иначе не поймёт, сколько ещё осталось взять.
     */
    private List<SubassemblyKittingCellRow> attachedRows(Long orderId) {

        return linkRepository.findByProductionOrderId(orderId).stream()
                .filter(link -> link.getSerial() != null)
                .map(link -> toCellRow(link.getSerial()))
                .toList();
    }

    /**
     * Внесённый состав набора заказов — одним запросом на страницу.
     *
     * Ключ карты — идентификатор заказа; порядок внесения сохраняется,
     * он же задаёт сопоставление с экземплярами плана (см.
     * {@code GroupZnpDistributionService}).
     */
    private Map<Long, List<SubassemblyKittingCellRow>> attachedRowsByOrder(
            List<Long> orderIds) {

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<SubassemblyKittingCellRow>> result =
                new LinkedHashMap<>();

        for (SerialProductionOrder link
                : linkRepository.findAttachedByOrderIds(orderIds)) {

            if (link.getSerial() == null
                    || link.getProductionOrder() == null
                    || link.getProductionOrder().getId() == null) {
                continue;
            }

            result.computeIfAbsent(
                            link.getProductionOrder().getId(),
                            k -> new ArrayList<>()
                    )
                    .add(toCellRow(link.getSerial()));
        }

        return result;
    }

    /*
     * Ячейка → строка перечня.
     *
     * Один конструктор на складской остаток и на внесённый состав:
     * иначе два отобра разъехались бы по составу печатаемых полей, и
     * один и тот же номер выглядел бы на экране по-разному.
     */
    private static SubassemblyKittingCellRow toCellRow(ProductSerial serial) {

        return new SubassemblyKittingCellRow(
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
        );
    }

    /**
     * Складской остаток, допустимый для заказа: свободные ячейки того
     * же изделия, что заказано.
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
            return serialRepository.findFreeOnWarehouse();
        }

        return serialRepository.findFreeOnWarehouse().stream()
                .filter(serial -> serial.getProductClassifier() != null
                        && classifierId.equals(
                                serial.getProductClassifier().getId()))
                .toList();
    }

    /**
     * Свободные ячейки заказа: склад, отфильтрованный по изделию заказа.
     *
     * Кладовщик должен видеть только то, что входит в заказ, — иначе
     * среди «подсборок» он может пропустить чужие ячейки. Отметка
     * «внесена» здесь не ставится: внесённые ячейки в этот перечень уже
     * не попадают, они читаются матрицей заказа.
     */
    private List<SubassemblyKittingCellRow> freeCells(
            ProductionOrder order,
            List<SubassemblyKittingCellRow> allStock) {

        Long classifierId = order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();

        return allStock.stream()
                .filter(row -> classifierId == null
                        || classifierId.equals(row.classifierId()))
                .toList();
    }

    /**
     * Заказов, где внесено меньше заказанного: счётчик пункта меню.
     *
     * Считается тем же списком и тем же признаком, что печатает экран
     * ({@link SubassemblyKittingZnpRow#incomplete()}), а не отдельным
     * запросом «сколько заказов в работе»: цифра меню обязана равняться
     * числу строк «Взято X из Y», где X меньше Y, иначе кладовщик шёл
     * бы по счётчику на экран, где брать уже нечего.
     *
     * У заказа без заданного количества (комплектование внутри выпуска)
     * знаменатель — складской остаток плюс внесённое, поэтому такой заказ
     * считается неполным, пока по его изделию на складе лежит хоть одна
     * свободная ячейка. Именно так же его печатает список.
     */
    @Transactional(readOnly = true)
    public long countPending() {
        return findOrders().stream()
                .filter(SubassemblyKittingZnpRow::incomplete)
                .count();
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
     *
     * Для заказа с {@code kittingType=SUBASSEMBLY} (V72) — режим
     * «изготовление ячеек из подсборок»: берутся непринятые подсборки
     * и при внесении принимаются на склад как ячейки.
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
         * Режим V72: «Изготовление ячеек из подсборок».
         *
         * Берём непринятые подсборки из findUnacceptedCells(), вносим
         * в матрицу и принимаем на склад как ячейки (markAccepted +
         * recordAccepted). Подсборка становится ячейкой — тот самый
         * шаг, который переводит QuarantineCellKind из SUBASSEMBLY в CELL.
         */
        if (order.isSubassemblyKitting()) {
            return attachUnacceptedCells(order, chosen, executor);
        }

        /*
         * Обычный режим: принятые складом ячейки.
         *
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

            performSerialOperation(serial, order, operation, executor);
            completeScheduleRow(order, operation, executor);
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

    /**
     * Режим V72: внесение непринятых подсборок с приёмкой на склад.
     *
     * Подсборка ещё не принята на склад (acceptedAt IS NULL), и внесение
     * в заказ — тот самый шаг, который переводит её в статус ячейки.
     * Сервис вносит подсборки в матрицу комплектования, принимает их
     * на склад (markAcceptedByIds + recordAccepted) и проводит
     * комплектовочную операцию.
     *
     * Отличие от обычного режима: stock — это непринятые подсборки
     * (findUnacceptedCells), а не принятые ячейки (findFreeOnWarehouse).
     * После внесения подсборка становится ячейкой — acceptedAt ставится
     * на все внесённые номера одним UPDATE, а журнал получает событие
     * ACCEPTED с cellKind=CELL.
     */
    private KittingResult attachUnacceptedCells(
            ProductionOrder order,
            List<String> chosen,
            AppUser executor) {

        /*
         * Берём непринятые подсборки по изделию заказа.
         *
         * loadView уже отфильтровал по классификатору, и здесь
         * тот же отбор — кладовщик не должен видеть чужие изделия.
         */
        List<ProductSerial> unaccepted = unacceptedStock(order);

        /*
         * Внесённые ранее номера читаются матрицей комплектования
         * (V20) — она же даёт и счётчик уже вошедших: повторный
         * запрос count по тому же заказу был бы вторым обращением
         * за тем же множеством строк.
         *
         * Матрица — источник истины: если строка уже есть, подсборка
         * в заказе, и принимать её заново нельзя. Счётчик по матрице,
         * а не по принятым номерам, потому что в режиме V72 подсборка
         * попадает в заказ и принимается одной транзакцией.
         */
        Set<Long> alreadyAttachedIds = new HashSet<>();

        for (SerialProductionOrder link
                : linkRepository.findByProductionOrderId(order.getId())) {

            if (link.getSerial() != null) {
                alreadyAttachedIds.add(link.getSerial().getId());
            }
        }

        long already = alreadyAttachedIds.size();

        /*
         * Собираем список новых подсборок ДО записи в базу.
         *
         * Заказанное количество — граница, и проверить её нужно
         * на всём перечне сразу. Проводить по одной до первого отказа
         * значило бы оставить заказ половину списка принявшим,
         * а половину — нет.
         */
        List<ProductSerial> attached = new ArrayList<>();

        for (String number : chosen) {

            ProductSerial serial = unaccepted.stream()
                    .filter(s -> number.equals(s.getFullNumber()))
                    .findFirst()
                    .orElse(null);

            if (serial == null) {
                continue;
            }

            /*
             * Уже в матрице — пропускаем.
             *
             * Это может случиться, если кладовщик ввёл номера повторно:
             * первый проход записал в матрицу, но транзакция ещё не
             * завершилась, и acceptedAt ещё не стоит.
             */
            if (alreadyAttachedIds.contains(serial.getId())) {
                continue;
            }

            attached.add(serial);
        }

        if (attached.isEmpty()) {
            return KittingResult.rejected(
                    already > 0
                            ? "Все внесённые подсборки уже входят в заказ «"
                            + order.getNumber() + "»"
                            : "Внесите номера подсборок, которые берёте в заказ «"
                            + order.getNumber() + "»"
            );
        }

        /*
         * Заказанное количество — потолок состава.
         *
         * Количество задают только групповым и ремонтным видам; у
         * заказа на комплектование количества нет, и ограничивать нечем.
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

        /*
         * Операция приёмки на склад полуфабрикатов.
         *
         * Для режима V72 ищем операцию в техпроцессе — она уже есть
         * (тип KITTING_SUBASSEMBLY, подразделение СПФ), и тот же метод,
         * что и для обычного режима, её найдёт.
         */
        Operation operation = findKittingOperation(order);

        if (operation == null) {
            throw new IllegalStateException(
                    "В заказе «" + order.getNumber()
                            + "» не задана операция комплектования "
                            + "подсборок (KITTING_SUBASSEMBLY)"
            );
        }

        LocalDateTime moment = LocalDateTime.now();

        /*
         * Вносим в матрицу комплектования.
         *
         * Та же таблица V20, что и для обычного режима: каждая
         * подсборка связывается с заказом и операцией.
         */
        List<Long> attachedIds = new ArrayList<>();

        for (ProductSerial serial : attached) {

            SerialProductionOrder link = new SerialProductionOrder();

            link.setSerial(serial);
            link.setProductionOrder(order);
            link.setOperation(operation);
            link.setCreatedBy(executor);
            link.setNote("Комплектование подсборок на складе (V72)");

            linkRepository.save(link);
            attachedIds.add(serial.getId());

            /*
             * Проводим операцию изделия — та же логика, что и для
             * обычного режима: создаёт или закрывает строку
             * product_serial_operations.
             */
            performSerialOperation(serial, order, operation, executor);

            /*
             * Проводим строку плана заказа — разблокирует следующую
             * операцию маршрута («Монтаж подсборок»).
             */
            completeScheduleRow(order, operation, executor);
        }

        /*
         * Принимаем подсборки на склад как ячейки.
         *
         * bulk-UPDATE: acceptedAt = now, acceptedBy = executor.
         * Подсборка становится ячейкой — QuarantineCellKind
         * переключается из SUBASSEMBLY в CELL.
         */
        serialRepository.markAcceptedByIds(attachedIds, moment, executor);

        /*
         * Журнал перемещений: событие ACCEPTED с cellKind=CELL.
         *
         * В паспорте ячейки будет видно, что подсборка принята
         * на склад как ячейка в рамках этого заказа.
         */
        journal.recordAccepted(attached, executor, moment);

        /*
         * Оприходование в производство — для общего учёта.
         *
         * Те же serials, что были приняты — теперь они официально
         * в составе заказа.
         */
        journal.recordInProduction(attached, order, executor);

        return KittingResult.accepted(
                "Принято на склад и внесено в заказ «" + order.getNumber()
                        + "»: " + attached.size() + " яч."
                        + (already > 0
                                ? "; уже вошло ранее " + already + " яч."
                                : "")
        );
    }

    /**
     * Непринятые подсборки для режима V72: stock для attachUnacceptedCells.
     *
     * Тот же отбор по классификатору, что в loadView — кладовщик
     * не должен видеть чужие изделия.
     */
    private List<ProductSerial> unacceptedStock(ProductionOrder order) {

        Long classifierId = order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();

        List<ProductSerial> all = serialRepository.findUnacceptedCells();

        if (classifierId == null) {
            return all;
        }

        return all.stream()
                .filter(serial -> serial.getProductClassifier() != null
                        && classifierId.equals(
                                serial.getProductClassifier().getId()))
                .toList();
    }

    /**
     * Проведение операции изделия: создаёт или закрывает строку
     * product_serial_operations в рамках этого заказа.
     *
     * Без этой записи факт работы повис бы только в журнале
     * перемещений и не попал ни в матрицу изделия, ни в загрузку
     * исполнителя, а операция в маршруте осталась бы незакрытой —
     * следующая по зависимостям не разблокировалась бы.
     *
     * Если по этой же операции уже есть незакрытая попытка —
     * проводится она, а не создаётся новая.
     */
    private void performSerialOperation(
            ProductSerial serial,
            ProductionOrder order,
            Operation operation,
            AppUser user) {

        /*
         * Уже закрытая попытка этой же операции — закрываем её,
         * а не создаём дубликат.
         */
        Optional<ProductSerialOperation> last =
                serialOperationRepository
                        .findFirstBySerialIdAndOperationIdOrderByAttemptDesc(
                                serial.getId(), operation.getId());

        Optional<ProductSerialOperation> open = last
                .filter(existing -> existing.getActualDate() == null
                        && !existing.isReworked());

        if (open.isPresent()) {

            ProductSerialOperation row = open.get();

            row.setProductionOrder(order);
            row.setStatus(ProductSerialOperationStatus.DONE);
            row.setActualDate(java.time.LocalDate.now());
            row.setExecutor(user);
            row.setPerformedBy(user);

            serialOperationRepository.save(row);

            return;
        }

        /*
         * Новой попытки нет — создаём строку со статусом DONE:
         * операция выполнена при внесении ячейки в заказ.
         */
        ProductSerialOperation row = new ProductSerialOperation();

        row.setSerial(serial);
        row.setOperation(operation);
        row.setProductionOrder(order);
        row.setAttempt(last.map(e -> e.getAttempt() + 1).orElse(1));
        row.setStatus(ProductSerialOperationStatus.DONE);
        row.setActualDate(java.time.LocalDate.now());
        row.setExecutor(user);
        row.setPerformedBy(user);
        row.setSeq(1);
        row.setNote("Комплектование подсборок на складе");

        serialOperationRepository.save(row);
    }

    /**
     * Проведение строки плана заказа по внесённой ячейке (V56).
     *
     * Цех живёт планом: пока строка «Комплектование подсборок» не
     * проведена, зависимость маршрута держит «Монтаж подсборок» закрытой
     * и мастер не увидит следующую работу. Экземпляров в плане столько,
     * сколько заказано изделий, поэтому каждая внесённая ячейка закрывает
     * ближайший непроведённый — порядок задан номером экземпляра, и
     * разблокировка следует за фактическим составом заказа.
     *
     * У заказа-комплектующего, вложенного в выпуск, строк этой ветви нет:
     * его работа планируется по заказу покупателя и проводится мастером в
     * «Мои операции», поэтому пустой результат здесь — норма, а не ошибка.
     */
    private void completeScheduleRow(
            ProductionOrder order,
            Operation operation,
            AppUser executor) {

        if (order.getId() == null || operation == null
                || operation.getId() == null) {
            return;
        }

        List<OrderScheduleRow> open = scheduleRowRepository
                .findOpenRowsOfProductionOrder(order.getId(), operation.getId());

        if (open.isEmpty()) {
            return;
        }

        OrderScheduleRow row = open.get(0);

        row.setCompletedAt(java.time.LocalDateTime.now());
        row.setCompletedBy(executor);
        scheduleRowRepository.save(row);
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
     * принимает.
     *
     * Групповой ЗНП режима V72 («изготовление ячеек из подсборок») —
     * исключение: он заводится самостоятельно, без выпуска-родителя
     * (V38), и при этом именно склад переводит его подсборки в ячейки.
     * Пускать его в раздел нужно по типу комплектования, а не по
     * уровню дерева.
     */
    private ProductionOrder requireKittingOrder(Long orderId) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Заказ на производство не найден"
                ));

        if (!order.isComponent() && !order.isSubassemblyKitting()) {
            throw new IllegalStateException(
                    "Комплектование подсборок проходит по заказу-"
                            + "комплектующему либо по групповому ЗНП с типом "
                            + "комплектования «из подсборок», а заказ «"
                            + order.getNumber() + "» ни то, ни другое"
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
     * Номер изменения изделия заказа — по последней партии прихода.
     *
     * Нужен подписи «5006 — ПВВЗ изм.3»: у одного классификатора партии
     * бывают разных изменений, и без него кладовщик не отличит подсборку
     * изменения 2 от изменения 3.
     */
    private String latestChangeNumber(ProductionOrder order) {

        Long classifierId = order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();

        if (classifierId == null) {
            return null;
        }

        List<String> numbers =
                batchRepository.findChangeNumbersOfClassifier(classifierId);

        return numbers.isEmpty() ? null : numbers.get(0);
    }

    /**
     * Номера изменений набора заказов — одним запросом на страницу.
     *
     * Ключ карты — идентификатор классификатора; запрос отсортирован от
     * свежих партий к старым, поэтому первая встреченная строка и есть
     * изменение последней партии ({@code putIfAbsent}).
     */
    private Map<Long, String> latestChangeNumbers(List<ProductionOrder> orders) {

        List<Long> classifierIds = orders.stream()
                .map(ProductionOrder::getProductClassifier)
                .filter(Objects::nonNull)
                .map(ProductClassifier::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (classifierIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> result = new LinkedHashMap<>();

        for (CellReceivingBatchRepository.ChangeNumberView view
                : batchRepository.findChangeNumbersByClassifiers(
                        classifierIds)) {

            if (view.getClassifierId() != null) {
                result.putIfAbsent(
                        view.getClassifierId(), view.getChangeNumber());
            }
        }

        return result;
    }

    /**
     * Экран комплектования: заказ, свободный склад, внесённый состав и
     * подпись операции приёмки.
     *
     * Два перечня держатся раздельно, а не одним списком с отметкой:
     * оформленная в ЗНП ячейка со склада снята, и показывать её в
     * доступных значило бы предлагать кладовщику провести её повторно.
     *
     * @param cells             свободный остаток склада по изделию заказа
     * @param attached          ячейки, уже оформленные в этот заказ (матрица V20)
     * @param quarantine        ячейки и подсборки изолятора брака
     * @param operationName     наименование операции комплектования
     * @param changeNumber      номер изменения изделия — для подписи
     *                          «5006 — ПВВЗ изм.3»
     */
    public record SubassemblyKittingView(
            ProductionOrder order,
            List<SubassemblyKittingCellRow> cells,
            List<SubassemblyKittingCellRow> attached,
            List<QuarantineCellView> quarantine,
            String operationName,
            String changeNumber
    ) {

        /**
         * Изделие заказа с номером изменения: «5006 — ПВВЗ изм.3».
         *
         * Номер изменения печатается по последней партии прихода того
         * же классификатора — это значение кладовщик вводил при
         * оприходовании. Пустое передаётся как есть.
         */
        public String getProductLabel() {
            return order == null
                    ? "—"
                    : order.getClassifierLabelWithChange(changeNumber);
        }

        /**
         * Термин «подсборки» вместо «ячейки» для экрана V72.
         *
         * Режим «изготовление ячеек из подсборок» работает с теми
         * изделиями, которые ещё не приняты ячейками, и заголовок
         * блока свободных позиций обязан говорить «подсборки», иначе
         * кладовщик не поймёт, какие номера вводить.
         */
        public boolean showSubassemblyLabel() {
            return order != null && order.isSubassemblyKitting();
        }

        /** Ячеек уже внесено в заказ. */
        public long takenCount() {
            return attached == null ? 0L : attached.size();
        }

        /** Ячеек свободно на складе. */
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
         * знаменателем остаётся всё, что побывало по этому изделию на
         * складе: свободный остаток плюс уже оформленное в заказ.
         */
        public long requiredCount() {
            BigDecimal quantity = order == null
                    ? null
                    : order.getQuantity();

            return quantity == null
                    ? takenCount() + availableCount()
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
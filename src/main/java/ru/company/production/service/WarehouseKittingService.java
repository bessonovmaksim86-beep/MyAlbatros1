package ru.company.production.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.dto.KittingZnpOperationRow;
import ru.company.production.dto.KittingZnpSerialRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.KittingZnpLinkView;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.ZnpCellLinkView;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Комплектование склада: операции, которые планировщик создал при
 * планировании заказа покупателя.
 *
 * Разделов два, и оба видны каждой роли склада, потому что склады
 * работают по одному маршруту и различаются только подразделением:
 * склад комплектования собирает ЗНП, склад полуфабрикатов отдаёт
 * полуфабрикаты. Содержимое при этом всегда берётся по подразделению
 * самого раздела, а не по роли вошедшего: кладовщик, открывший чужой
 * раздел, видит работу того склада, на который смотрит, — так реестр
 * складов остаётся читаемым для обоих.
 *
 * Работой склада считаются получение комплектации и комплектовочная
 * операция: их планировщик закрепляет за подразделением, а не за
 * человеком, и цеховые этапы маршрута в раздел не попадают.
 */
@Service
@RequiredArgsConstructor
public class WarehouseKittingService {

    /**
     * Раздел комплектования: его подразделение склада и подписи.
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
                "warehouse/kitting/znp"
        ),

        /** Склад полуфабрикатов отдаёт полуфабрикаты в сборку. */
        SEMI_FINISHED(
                "SEMI_FINISHED_PRODUCT_WAREHOUSE",
                "Комплектование полуфабрикатов",
                "Полуфабрикаты склада: изделия, которые склад выдаёт "
                        + "в сборку по плану заказа покупателя",
                "kitting-semi",
                "warehouse/kitting/semi"
        );

        private final String unitCode;
        private final String title;
        private final String description;
        private final String activePage;
        private final String view;

        /** Идентификатор раздела для адресной строки. */
        public String param() {
            return this == ZNP ? "znp" : "semi";
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

            bySerial
                    .computeIfAbsent(
                            serial.getSerialId(),
                            key -> new ArrayList<>()
                    )
                    .add(new KittingZnpOperationRow(
                            row.getId(),
                            row.getOperation().getOperationName() == null
                                    ? null
                                    : row.getOperation()
                                            .getOperationName().getName(),
                            znpNumber,
                            row.getPlannedDate(),
                            completed,
                            completed
                                    ? row.getCompletedAt().format(RU_DATE_TIME)
                                    : null,
                            blocker != null,
                            blocker,
                            !completed && blocker == null,
                            cellsByZnp.getOrDefault(znpNumber, List.of())
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

        if (row == null || row.getOperation() == null
                || row.getProductionOrder() == null) {
            return KittingResult.rejected(
                    "Строка плана не найдена — привязать ячейку не к чему"
            );
        }

        ProductionOrder order = row.getProductionOrder();

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
         * Потолок состава меряется ВСЕМИ строками операции заказа:
         * заказ на три изделия даёт три строки графика, и четвёртая
         * ячейка означала бы, что склад переложил заказ.
         */
        long required = scheduleRowRepository
                .countRowsOfProductionOrderAndOperation(
                        order.getId(), operation.getId());

        long already = serialProductionOrderRepository
                .countByProductionOrder_Id(order.getId());

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

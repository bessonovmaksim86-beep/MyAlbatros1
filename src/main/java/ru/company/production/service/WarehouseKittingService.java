package ru.company.production.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.dto.KittingZnpOperationRow;
import ru.company.production.dto.KittingZnpSerialRow;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.repository.KittingZnpLinkView;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.SerialProductionOrderRepository;

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

    /** Подпись даты проведения в раскрытии изделия. */
    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

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

        for (KittingZnpLinkView link
                : serialProductionOrderRepository
                        .findZnpLinksBySerialIds(serialIds)) {

            znpByPair.put(
                    pairKey(link.getSerialId(), link.getOperationId()),
                    link.getZnpNumber()
            );
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
                            !completed && blocker == null
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
}

package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.WarehouseSerialSearchFilter;
import ru.company.production.dto.WarehouseSerialSearchRow;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.UnitHandoverRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Поиск серийного номера повреждённой этикетки.
 *
 * Сервис ищет серийные номера по набору наводок: классификатор,
 * наименование изделия, частичное совпадение номера, операция,
 * на которой обнаружена повреждённая этикетка, перечень пройденных
 * операций, статус, период, заказ-комплектующий и прочие признаки.
 *
 * Пройдённые операции проверяются в два этапа:
 * 1) базовый запрос отбирает номера по текстовым наводкам;
 * 2) пакетный запрос выбирает маршрут всех отобранных номеров
 *    одной пачкой, и сервис вычитает те, кому не хватает
 *    хотя бы одной из «пройдённых» операций.
 */
@Service
@RequiredArgsConstructor
public class WarehouseSerialSearchService {

    private final ProductSerialRepository serialRepository;
    private final UnitHandoverRepository handoverRepository;

    /**
     * Поиск серийных номеров по наводкам.
     */
    @Transactional(readOnly = true)
    public List<WarehouseSerialSearchRow> search(
            WarehouseSerialSearchFilter filter) {

        WarehouseSerialSearchFilter applied =
                filter == null ? WarehouseSerialSearchFilter.empty() : filter;

        LocalDateTime dateFromStart = applied.dateFrom() == null
                ? null
                : applied.dateFrom().atStartOfDay();

        LocalDateTime dateToEnd = applied.dateTo() == null
                ? null
                : applied.dateTo().plusDays(1).atStartOfDay();

        String supplierKey = applied.supplierName() == null
                ? null
                : applied.supplierName().trim().toLowerCase(
                        Locale.forLanguageTag("ru"));

        Long operationFoundId = applied.operationFound() == null
                ? null
                : applied.operationFound().operationNameId();

        List<Long> completedOpIds = applied.completedOperations() == null
                ? Collections.emptyList()
                : applied.completedOperations().stream()
                    .filter(op -> op.operationNameId() != null)
                    .map(WarehouseSerialSearchFilter.OperationFilter::operationNameId)
                    .toList();

        List<ProductSerialRepository.SerialHintSearchView> views =
                serialRepository.findSerialsByHints(
                        applied.classifierId(),
                        blankToNull(applied.productName()),
                        blankToNull(applied.numberHint()),
                        applied.status(),
                        applied.productType(),
                        applied.inputControlType(),
                        dateFromStart,
                        dateToEnd,
                        supplierKey,
                        blankToNull(applied.incomingOrderNumber()),
                        blankToNull(applied.znpNumber()),
                        blankToNull(applied.productionOrderNumber()),
                        operationFoundId
                );

        if (views.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> idsWithAllOps = filterByCompletedOps(views, completedOpIds);

        List<Long> serialIds = views.stream()
                .map(ProductSerialRepository.SerialHintSearchView::getId)
                .toList();

        Map<Long, String> handedOverUnits = new HashMap<>();
        if (!serialIds.isEmpty()) {
            handoverRepository.findPendingUnitNamesBySerialIds(serialIds)
                    .forEach(v -> {
                        if (v.getSerialId() != null && v.getUnitName() != null) {
                            handedOverUnits.putIfAbsent(v.getSerialId(), v.getUnitName());
                        }
                    });
        }

        Map<Long, List<String>> opsBySerial = loadOperationsBySerial(serialIds);

        String unitHint = lower(applied.unitName());

        return views.stream()
                .filter(v -> idsWithAllOps.contains(v.getId()))
                .filter(v -> unitHint == null
                        || lower(handedOverUnits.get(v.getId())) != null
                           && lower(handedOverUnits.get(v.getId()))
                                   .contains(unitHint))
                .map(v -> toRow(v, handedOverUnits.get(v.getId()),
                        opsBySerial.getOrDefault(v.getId(), Collections.emptyList())))
                .toList();
    }

    /**
     * Все операции, встречающиеся в маршрутах — справочник для фильтра.
     */
    @Transactional(readOnly = true)
    public List<OperationOption> findAllOperations() {
        return serialRepository.findOperationNamesInUse().stream()
                .map(op -> new OperationOption(op.getId(), op.getName()))
                .toList();
    }

    /**
     * Опции классификаторов — для выпадающего списка на форме поиска.
     */
    @Transactional(readOnly = true)
    public List<ClassifierOption> findAllClassifiers() {
        return serialRepository.findSerialClassifierOptions().stream()
                .map(opt -> new ClassifierOption(
                        opt.getId(), opt.getCode(),
                        opt.getName(), opt.getSensorName()))
                .toList();
    }

    private Set<Long> filterByCompletedOps(
            List<ProductSerialRepository.SerialHintSearchView> views,
            List<Long> completedOpIds) {

        if (completedOpIds.isEmpty()) {
            return views.stream()
                    .map(ProductSerialRepository.SerialHintSearchView::getId)
                    .collect(Collectors.toSet());
        }

        List<Long> serialIds = views.stream()
                .map(ProductSerialRepository.SerialHintSearchView::getId)
                .toList();

        List<ProductSerialRepository.SerialOperationView> ops =
                serialRepository.findSerialOperations(serialIds);

        Map<Long, Set<Long>> opsBySerial = ops.stream()
                .collect(Collectors.groupingBy(
                        ProductSerialRepository.SerialOperationView::getSerialId,
                        Collectors.mapping(
                                ProductSerialRepository.SerialOperationView::getOperationNameId,
                                Collectors.toSet()
                        )
                ));

        return views.stream()
                .filter(v -> {
                    Set<Long> serialOps = opsBySerial.getOrDefault(
                            v.getId(), Collections.emptySet());
                    return serialOps.containsAll(completedOpIds);
                })
                .map(ProductSerialRepository.SerialHintSearchView::getId)
                .collect(Collectors.toSet());
    }

    private Map<Long, List<String>> loadOperationsBySerial(
            List<Long> serialIds) {

        if (serialIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ProductSerialRepository.SerialOperationView> ops =
                serialRepository.findSerialOperations(serialIds);

        return ops.stream()
                .collect(Collectors.groupingBy(
                        ProductSerialRepository.SerialOperationView::getSerialId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                ProductSerialRepository.SerialOperationView::getOperationName,
                                Collectors.toList()
                        )
                ));
    }

    private WarehouseSerialSearchRow toRow(
            ProductSerialRepository.SerialHintSearchView v,
            String unitName,
            List<String> opNames) {

        return new WarehouseSerialSearchRow(
                v.getId(),
                v.getClassifierCode(),
                v.getClassifierName(),
                v.getSensorClassName(),
                v.getProductType(),
                v.getSerial(),
                v.getStatus(),
                v.getZnpNumber(),
                v.getIncomingOrderNumber(),
                v.getChangeNumber(),
                v.getSupplierName(),
                v.getProductionOrderNumber(),
                null,
                unitName,
                v.getInputControlType() == null
                        ? null
                        : v.getInputControlType().getDisplayName(),
                v.getInputControlAt(),
                v.getInputControlByName(),
                v.getAcceptedAt(),
                v.getAcceptedByName(),
                v.getCellReadyAt(),
                v.getCellReadyByName(),
                v.getIssuedAt(),
                opNames
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String lower(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.forLanguageTag("ru"));
    }

    public record OperationOption(Long id, String name) {
        public Long getId() { return id; }
        public String getName() { return name; }
    }

    /**
     * Опция фильтра «классификатор».
     *
     * Двойники вида getX() держат рядом с аксессорами record
     * намеренно: Thymeleaf читает свойства по JavaBeans-соглашению,
     * а record компилируется с аксессором <имя>() без префикса —
     * тот же приём, что у {@link ru.company.production.dto.WarehouseStockRow}.
     */
    public record ClassifierOption(
            Long id, Integer code, String name, String sensorName) {

        public String getLabel() {
            String title = name == null || name.isBlank()
                    ? (sensorName == null || sensorName.isBlank()
                            ? "Без наименования"
                            : sensorName.trim())
                    : name.trim();
            return code == null ? title : code + " — " + title;
        }

        public Long getId() { return id; }
        public Integer getCode() { return code; }
        public String getName() { return name; }
        public String getSensorName() { return sensorName; }
    }
}

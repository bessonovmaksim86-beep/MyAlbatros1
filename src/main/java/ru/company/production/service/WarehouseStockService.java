package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.WarehouseStockFilter;
import ru.company.production.dto.WarehouseStockRow;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Наличие на складе полуфабрикатов: реестр ячеек и подсборок, которые
 * сейчас на нём лежат.
 *
 * Раздел ничего не проводит и не меняет — он отвечает на вопрос
 * «что лежит на складе и где именно». Граница та же, что у разделов
 * комплектования: позицией склада считается номер рода «Ячейка»,
 * принята ли складом (ячейка) или ещё нет (подсборка) — различие
 * выводят из отметки приёмки, как и изолятор брака
 * ({@link QuarantineCellKind#ofAccepted}).
 *
 * Размещение берётся из матрицы комплектования (V20), а не из
 * признака приёмки: ячейку внесли в действующий ЗНП — со полки она
 * снята, и кладовщик обязан видеть это в реестре, иначе второй раз
 * выдал бы ту же подсборку. Снятый с работы заказ ячейку отдаёт
 * обратно, поэтому неактивные заказы в расчёт не берутся — то же
 * правило, что у складского остатка комплектования.
 *
 * Сортировки и отбор считаются в памяти по уже выбранной странице:
 * интервал дат и контрагент сужают выборку на стороне базы, а порядок
 * строк задаётся русским сопоставлением — сортировка по-русски в SQL
 * зависит от collation колонок и разошлась бы с порядком, который
 * человек видит в прочих перечнях.
 */
@Service
@RequiredArgsConstructor
public class WarehouseStockService {

    /**
     * Идентификаторов в одной порции запроса размещения.
     *
     * Реестр без фильтра перечисляет тысячи номеров, и один {@code in}
     * на весь перечень превратил бы запрос в утверждение с тысячей
     * параметров; порция держит его в разумных границах.
     */
    private static final int PLACEMENT_PORTION = 500;

    /** Русское сопоставление: «Ячейка» и «яПР56» сортируются как человек. */
    private static final Collator RUSSIAN =
            Collator.getInstance(Locale.forLanguageTag("ru"));

    private final ProductSerialRepository serialRepository;
    private final SerialProductionOrderRepository linkRepository;

    /**
     * Позиции склада по фильтру — строки реестра в выбранном порядке.
     *
     * Размещение читается одним пакетным запросом на весь перечень:
     * выборка матрицы на каждую строку дала бы N+1 на экране, где
     * строк сотни.
     */
    @Transactional(readOnly = true)
    public List<WarehouseStockRow> findRows(WarehouseStockFilter filter) {

        WarehouseStockFilter applied =
                filter == null ? WarehouseStockFilter.empty() : filter;

        List<ProductSerialRepository.WarehouseStockProjection> rows =
                serialRepository.findWarehouseStock(
                        applied.kind() == null
                                ? null
                                : applied.kind().name(),
                        applied.classifierId(),
                        applied.normalizedSupplierKey(),
                        applied.fromMoment(),
                        applied.toMoment()
                );

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, Set<String>> znpBySerial = placements(rows);

        List<WarehouseStockRow> result = new ArrayList<>(rows.size());

        for (ProductSerialRepository.WarehouseStockProjection row : rows) {

            Set<String> orders = row.getId() == null
                    ? Set.of()
                    : znpBySerial.getOrDefault(row.getId(), Set.of());

            result.add(new WarehouseStockRow(
                    row.getId(),
                    row.getClassifierId(),
                    row.getClassifierCode(),
                    row.getClassifierName(),
                    row.getSensorClassName(),
                    row.getProductType(),
                    row.getSerial() == null ? 0 : row.getSerial(),
                    row.getStatus(),
                    row.getZnpNumber(),
                    row.getBatchId(),
                    row.getSupplierName(),
                    row.getIncomingOrderNumber(),
                    row.getChangeNumber(),
                    row.getBatchQuantity(),
                    row.getBatchAcceptedAt(),
                    row.getAcceptedAt(),
                    row.getReceivedAt(),
                    orders.isEmpty() ? null : String.join(", ", orders),
                    orders.isEmpty()
            ));
        }

        sort(result, applied.sortingOrDefault());

        return List.copyOf(result);
    }

    /**
     * Сводка реестра: всего позиций, из них ячеек, подсборок и
     * свободных.
     *
     * Считается по уже отобранным строкам, а не отдельными запросами:
     * сводка обязана описывать ровно тот перечень, что под ней, —
     * «2 400 ячеек» над списком в 37 строк читалось бы как ошибка, а
     * второй проход по тому же отбору стоил бы ещё одного обращения к
     * базе ради цифр, которые уже в руках.
     */
    public StockSummary summarize(List<WarehouseStockRow> rows) {

        long cells = rows.stream()
                .filter(row -> row.getKind() == QuarantineCellKind.CELL)
                .count();

        long free = rows.stream()
                .filter(WarehouseStockRow::free)
                .count();

        return new StockSummary(rows.size(), cells, rows.size() - cells, free);
    }

    /** Опции фильтра «изделие»: только те, чьи позиции лежат на складе. */
    @Transactional(readOnly = true)
    public List<StockOption> findProductOptions() {

        return serialRepository.findWarehouseStockClassifiers().stream()
                .map(option -> new StockOption(
                        option.getId(),
                        option.getCode(),
                        option.getName(),
                        option.getSensorName()
                ))
                .toList();
    }

    /** Опции фильтра «контрагент»: написания складских приходов. */
    @Transactional(readOnly = true)
    public List<SupplierOption> findSupplierOptions() {

        /*
         * Порядок — русское сопоставление по написанию: список
         * поставщиков читают как перечень, и «Январь» после «Апреля»
         * означал бы, что фильтр ищут вслепую.
         */
        return serialRepository.findWarehouseStockSuppliers().stream()
                .map(option -> new SupplierOption(
                        option.getSupplierKey(),
                        option.getDisplayName()
                ))
                .sorted(Comparator.comparing(
                        SupplierOption::displayName,
                        (left, right) -> RUSSIAN.compare(
                                left == null ? "" : left,
                                right == null ? "" : right
                        )
                ))
                .toList();
    }

    /**
     * Действующие ЗНП каждой позиции — размещение на складе.
     *
     * Один запрос на порцию идентификаторов; связка «номер → заказы»
     * сохраняется в порядке выдачи матрицы, чтобы перечень заказов в
     * строке не менялся от перезагрузки страницы.
     */
    private Map<Long, Set<String>> placements(
            List<ProductSerialRepository.WarehouseStockProjection> rows) {

        List<Long> ids = rows.stream()
                .map(ProductSerialRepository.WarehouseStockProjection::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, Set<String>> result = new LinkedHashMap<>();

        for (int offset = 0; offset < ids.size(); offset += PLACEMENT_PORTION) {

            List<Long> portion = ids.subList(
                    offset,
                    Math.min(offset + PLACEMENT_PORTION, ids.size())
            );

            linkRepository.findZnpLinksBySerialIds(portion).forEach(link -> {

                if (link.getZnpNumber() == null
                        || link.getZnpNumber().isBlank()) {
                    return;
                }

                result.computeIfAbsent(
                                link.getSerialId(),
                                key -> new LinkedHashSet<>()
                        )
                        .add(link.getZnpNumber().trim());
            });
        }

        return result;
    }

    /**
     * Порядок строк выбранной сортировкой.
     *
     * Любая сортировка заканчивается номером: он уникален, поэтому
     * порядок строк не зависит от того, в каком виде база вернула
     * равные по ключу позиции, и страница не «прыгает» при
     * перезагрузке.
     */
    private void sort(List<WarehouseStockRow> rows,
                      WarehouseStockFilter.Sorting sorting) {

        Comparator<WarehouseStockRow> byNumber = Comparator
                .comparing(
                        (WarehouseStockRow row) -> row.classifierCode() == null
                                ? Integer.MAX_VALUE
                                : row.classifierCode()
                )
                .thenComparingInt(WarehouseStockRow::serial);

        Comparator<WarehouseStockRow> order = switch (sorting) {

            case NUMBER -> byNumber;

            case PRODUCT -> Comparator
                    .comparing(
                            WarehouseStockRow::getSortLabel,
                            RUSSIAN
                    )
                    .thenComparing(byNumber);

            /*
             * Пустой контрагент — не «самый первый» поставщик: позиции
             * без приходной партии уходят в конец, иначе они заняли бы
             * начало списка, отобранного по живому контрагенту.
             */
            case SUPPLIER -> Comparator
                    .comparing(
                            (WarehouseStockRow row) ->
                                    row.getSupplierKey().isEmpty(),
                            Comparator.naturalOrder()
                    )
                    .thenComparing(WarehouseStockRow::getSupplierKey)
                    .thenComparing(byNumber);

            case RECEIVED -> Comparator
                    .comparing(
                            WarehouseStockRow::receivedMoment,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    )
                    .thenComparing(byNumber);
        };

        rows.sort(order);
    }

    /** Опция фильтра изделия: код и наименование для подписи списка. */
    public record StockOption(
            Long id,
            Integer code,
            String name,
            String sensorName
    ) {

        /** Подпись опции: «5127 — ЯПР56М», пустое наименование названо. */
        public String getLabel() {
            String title = name == null || name.isBlank()
                    ? (sensorName == null || sensorName.isBlank()
                            ? "Без наименования"
                            : sensorName.trim())
                    : name.trim();

            return code == null ? title : code + " — " + title;
        }

        public Long getId() {
            return id;
        }

        public Integer getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getSensorName() {
            return sensorName;
        }
    }

    /** Опция фильтра контрагента: написание и его нормализованный ключ. */
    public record SupplierOption(String key, String displayName) {

        public String getKey() {
            return key;
        }

        /** Написание для показа: встреченное в партии, в исходном регистре. */
        public String getDisplayName() {
            return displayName == null || displayName.isBlank()
                    ? "Без наименования"
                    : displayName.trim();
        }
    }

    /**
     * Сводка над перечнем.
     *
     * @param total  строк в отобранном перечне
     * @param cells  из них принятых складом ячеек
     * @param subassemblies подсборок, приёмку которых ещё не провели
     * @param free   позиций, не оформленных ни в один действующий заказ
     */
    public record StockSummary(
            long total,
            long cells,
            long subassemblies,
            long free
    ) {

        /** Позиций, снятых с полок действующим заказом. */
        public long placed() {
            return total - free;
        }

        /*
         * Двойники вида getX() держат рядом с аксессорами record
         * намеренно: Thymeleaf читает свойства по JavaBeans-соглашению,
         * а record компилируется с аксессором <имя>() без префикса.
         */
        public long getTotal() {
            return total;
        }

        public long getCells() {
            return cells;
        }

        public long getSubassemblies() {
            return subassemblies;
        }

        public long getFree() {
            return free;
        }
    }
}

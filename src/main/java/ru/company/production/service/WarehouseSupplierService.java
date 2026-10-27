package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.SupplierForm;
import ru.company.production.dto.SupplierStatView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.WarehouseSupplier;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.SupplierStatProjection;
import ru.company.production.repository.WarehouseSupplierRepository;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Поставщики склада полуфабрикатов: справочник написаний и сводка
 * по приходам.
 *
 * Поставщик в партии — свободная строка, поэтому сводка считается не
 * по справочнику, а по строкам партий и группируется по
 * нормализованному написанию. Справочник нужен для другого: он
 * фиксирует, как имя пишется канонически, и подсказывает его в форме
 * оприходования. Отсюда и две группы строк в сводке: записанные в
 * справочнике и встреченные только в партиях — вторые видны
 * оператору как те, чьё написание стоит зафиксировать.
 */
@Service
@RequiredArgsConstructor
public class WarehouseSupplierService {

    private final WarehouseSupplierRepository supplierRepository;
    private final CellReceivingBatchRepository batchRepository;

    /**
     * Сводка по поставщикам: строки справочника плюс написания,
     * которые встретились только в партиях.
     *
     * Сортировка — по наименованию, а не по объёму: список читают
     * как перечень контрагентов, и порядок должен оставаться
     * предсказуемым от открытия к открытию.
     */
    @Transactional(readOnly = true)
    public List<SupplierStatView> findStats() {
        Map<String, SupplierStatProjection> stats = groupBySupplierKey(
                batchRepository.findSupplierStats()
        );

        List<SupplierStatView> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (WarehouseSupplier supplier : supplierRepository.findAllByOrderByNameAsc()) {
            String key = normalize(supplier.getName());

            result.add(view(
                    supplier.getName(),
                    supplier.getNote(),
                    stats.get(key),
                    true
            ));

            seen.add(key);
        }

        /* Написания из партий, для которых записи в справочнике нет. */
        stats.entrySet().stream()
                .filter(entry -> !seen.contains(entry.getKey()))
                .sorted(Comparator.comparing(
                        Map.Entry::getKey,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .forEach(entry -> result.add(view(
                        displayName(entry.getValue()),
                        null,
                        entry.getValue(),
                        false
                )));

        return List.copyOf(result);
    }

    /**
     * Заводит поставщика в справочник.
     *
     * Написание проверяется без учёта регистра и обрамляющих
     * пробелов: уникальность в БД держится на collation
     * utf8mb4_unicode_ci, но сообщение об ошибке должно появляться до
     * обращения к базе, а не как нарушение ограничения.
     */
    /** Наименования справочника — подсказкой в форму прихода. */
    @Transactional(readOnly = true)
    public List<String> findSupplierNames() {
        return supplierRepository.findAllByOrderByNameAsc().stream()
                .map(WarehouseSupplier::getName)
                .toList();
    }

    /**
     * Поставщики справочника для выбора в форме прихода.
     *
     * Форма принимает идентификатор, а не написание: приход обязан
     * ссылаться на заведённого поставщика, иначе свободный ввод
     * вернул бы ту самую проблему, из-за которой справочник и завели.
     */
    @Transactional(readOnly = true)
    public List<WarehouseSupplier> findAll() {
        return supplierRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public WarehouseSupplier create(SupplierForm form, AppUser author) {
        String name = form.getName() == null ? "" : form.getName().trim();

        if (name.isEmpty()) {
            throw new IllegalArgumentException(
                    "Укажите наименование поставщика"
            );
        }

        supplierRepository.findByNormalizedName(name).ifPresent(existing -> {
            throw new IllegalStateException(
                    "Поставщик «" + existing.getName()
                            + "» уже есть в справочнике"
            );
        });

        WarehouseSupplier supplier = new WarehouseSupplier();

        supplier.setName(name);
        supplier.setNote(trimToNull(form.getNote()));
        supplier.setCreatedBy(author);

        return supplierRepository.save(supplier);
    }

    private Map<String, SupplierStatProjection> groupBySupplierKey(
            List<SupplierStatProjection> rows
    ) {
        Map<String, SupplierStatProjection> result = new LinkedHashMap<>();

        for (SupplierStatProjection row : rows) {
            if (row.getSupplierKey() != null) {
                result.put(row.getSupplierKey(), row);
            }
        }

        return result;
    }

    private SupplierStatView view(
            String name,
            String note,
            SupplierStatProjection stats,
            boolean registered
    ) {
        if (stats == null) {
            return new SupplierStatView(
                    name,
                    note,
                    0L,
                    0L,
                    0L,
                    0L,
                    "—",
                    registered
            );
        }

        return new SupplierStatView(
                name,
                note,
                stats.getBatchCount(),
                stats.getCellCount(),
                stats.getReceivedCells(),
                stats.getPendingBatches(),
                SupplierStatView.format(stats.getLastReceivedAt()),
                registered
        );
    }

    /**
     * Написание для строки без записи в справочнике: в партиях один
     * поставщик может быть записан по-разному, показываем любое из
     * встреченных написаний в исходном регистре.
     */
    private String displayName(SupplierStatProjection stats) {
        return stats.getDisplayName() == null
                ? "Без наименования"
                : stats.getDisplayName();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
                .toLowerCase(Locale.forLanguageTag("ru"));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}

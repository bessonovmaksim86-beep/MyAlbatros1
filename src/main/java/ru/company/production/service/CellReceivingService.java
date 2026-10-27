package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CellReceivingForm;
import ru.company.production.dto.CellScanningResult;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.WarehouseSupplier;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.WarehouseSupplierRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Оприходование ячеек на складе полуфабрикатов.
 *
 * Процесс состоит из шагов, и каждый заканчивается записью: оператор
 * не может «вернуться» к уже напечатанным номерам, не оставив следа.
 *
 *   1. приход (поставщик, ордер, количество, ячейка, изменение) —
 *      под него резервируется непрерывный диапазон серий;
 *   2. печать QR — партия помечается напечатанной;
 *   3. подтверждение и сканирование — каждая ячейка вводится с
 *      считывателя;
 *   4. только при полностью совпавшей партии номера попадают в реестр.
 *
 * Резерв диапазона отделён от записи в реестр намеренно: между
 * печатью и сканированием проходит время, и номера нельзя держать
 * свободными, иначе их займёт заказ на производство.
 *
 * Нумерация — по классификатору ячейки (см. {@link SerialNumbering}):
 * последний номер этого классификатора плюс единица.
 */
@Service
@RequiredArgsConstructor
public class CellReceivingService {

    /* Верхняя граница пятизначной серии (chk_product_serial_range). */
    private static final int MAX_SERIAL = 99999;

    /* Длина полного номера: 4 цифры кода + 5 цифры серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    private final CellReceivingBatchRepository batchRepository;
    private final ProductSerialRepository productSerialRepository;
    private final ProductClassifierRepository classifierRepository;
    private final WarehouseSupplierRepository supplierRepository;

    /* Журнал перемещений: приход — начало истории каждого номера. */
    private final SerialMovementJournalService journal;

    /** Все партии, новые сверху. */
    @Transactional(readOnly = true)
    public List<CellReceivingBatch> findAll() {
        return batchRepository.findAllDetailed();
    }

    /** Партии, не доведённые до сканирования, — счётчик в меню. */
    @Transactional(readOnly = true)
    public long countPending() {
        return batchRepository.countByConfirmedAtIsNull();
    }

    /** Ячеек, уже внесённых приходом склада в реестр. */
    @Transactional(readOnly = true)
    public long countReceivedCells() {
        return productSerialRepository.countByReceivingBatchIsNotNull();
    }

    @Transactional(readOnly = true)
    public CellReceivingBatch get(Long id) {
        return batchRepository.findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Партия оприходования не найдена"
                ));
    }

    /**
     * Ячейки для выбора в форме прихода.
     *
     * Ограничение типом обязательно: номер складывается из кода
     * классификатора, и приход ячейки на ряд прибора исказил бы оба
     * ряда номеров.
     */
    /**
     * Ячейки для выбора в форме прихода.
     *
     * Ограничение типом обязательно: номер складывается из кода
     * классификатора, и приход ячейки на ряд прибора исказил бы оба
     * ряда номеров.
     */
    @Transactional(readOnly = true)
    public List<ProductClassifier> findCellClassifiers() {
        return classifierRepository.findAllByOrderByCodeAsc().stream()
                .filter(item -> item.getProductType() == ProductType.CELL)
                .toList();
    }

    /**
     * Формирует партию и резервирует под неё номера.
     *
     * Первый номер — следующий после последнего номера ряда этого
     * классификатора. Предел берётся из двух мест: реестр (номера уже
     * выпущенных изделий) и ранее зарезервированные диапазоны партий,
     * которые в реестр ещё не попали.
     */
    @Transactional
    public CellReceivingBatch create(CellReceivingForm form, AppUser author) {

        ProductClassifier classifier = requireCellClassifier(
                form.getCellClassifierId()
        );

        int quantity = form.getQuantity();

        int first = nextSerial(classifier);
        int last = first + quantity - 1;

        if (last > MAX_SERIAL) {
            throw new IllegalStateException(
                    "Нумерации классификатора «" + classifier.getCode()
                            + "» не хватает: после " + format(classifier, last)
                            + " ряд пятизначных серий закончился"
            );
        }

        CellReceivingBatch batch = new CellReceivingBatch();

        WarehouseSupplier supplier = requireSupplier(form.getSupplierId());

        batch.setSupplier(supplier);
        batch.setSupplierName(supplier.getName());
        batch.setIncomingOrderNumber(form.getIncomingOrderNumber().trim());
        batch.setChangeNumber(form.getChangeNumber().trim());
        batch.setQuantity(quantity);
        batch.setCellClassifier(classifier);
        batch.setFirstSerial(first);
        batch.setLastSerial(last);
        batch.setNote(trimToNull(form.getNote()));
        batch.setCreatedBy(author);

        return batchRepository.save(batch);
    }

    /**
     * Отмечает, что номера партии переданы в программу печати.
     *
     * Сама печать — вне системы: программа рассылки этикеток будет
     * подключена позже, поэтому шаг фиксирует только факт, что
     * оператор перешёл к нему.
     */
    @Transactional
    public CellReceivingBatch markQrPrinted(Long id) {

        CellReceivingBatch batch = get(id);

        if (batch.getQrPrintedAt() == null) {
            batch.setQrPrintedAt(LocalDateTime.now());

            return batchRepository.save(batch);
        }

        return batch;
    }

    /**
     * Проверяет отсканированную партию и, если она совпала целиком,
     * вносит ячейки в реестр.
     *
     * Проверка строгая и не частичная: не полностью введённая партия,
     * задвоенный или чужой номер означают, что на складе лежит не то,
     * что оформлено приходом. Молча принять половину — значит
     * оставить реестр расходящимся с фактом, поэтому при любой
     * нестыковке в реестр не записывается ни одна ячейка, а оператор
     * получает перечень проблем.
     */
    @Transactional
    public CellScanningResult confirm(Long id, List<String> scannedLines) {

        CellReceivingBatch batch = get(id);

        if (!batch.isPending()) {
            return new CellScanningResult(
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Партия уже оприходована — повторное сканирование не требуется"
            );
        }

        Map<String, Integer> scanned = countScanned(batch, scannedLines);

        List<String> duplicates = scanned.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        Set<String> expected = new LinkedHashSet<>(batch.fullNumbers());

        List<String> foreign = scanned.keySet().stream()
                .filter(number -> !expected.contains(number))
                .toList();

        List<String> malformed = scannedLines == null
                ? List.of()
                : scannedLines.stream()
                .map(CellReceivingService::normalize)
                .filter(value -> !value.isEmpty())
                .filter(value -> !value.matches("\\d{" + FULL_NUMBER_LENGTH + "}"))
                .distinct()
                .toList();

        List<String> missing = expected.stream()
                .filter(number -> !scanned.containsKey(number))
                .toList();

        if (!missing.isEmpty()
                || !duplicates.isEmpty()
                || !foreign.isEmpty()
                || !malformed.isEmpty()) {

            return new CellScanningResult(
                    false,
                    missing,
                    duplicates,
                    foreign,
                    malformed,
                    describeProblem(missing, duplicates, foreign, malformed)
            );
        }

        /*
         * Резерв мог пересечься с выпуском: заказ на производство
         * создаётся в другом окне, и его номера в реестре появились
         * уже после того, как партия сформирована.
         */
        List<Integer> occupied = productSerialRepository.findSerialsInRange(
                SerialNumbering.keyFor(batch.getCellClassifier()),
                batch.getFirstSerial(),
                batch.getLastSerial()
        );

        if (!occupied.isEmpty()) {
            List<String> numbers = occupied.stream()
                    .map(batch::fullNumber)
                    .toList();

            return new CellScanningResult(
                    false,
                    numbers,
                    List.of(),
                    List.of(),
                    List.of(),
                    "Номера «" + String.join(", ", numbers)
                            + "» уже числятся в реестре — партию сформировать "
                            + "не удалось, оформите приход заново"
            );
        }

        writeSerials(batch);

        batch.setConfirmedAt(LocalDateTime.now());
        batchRepository.save(batch);

        /*
         * Начало пути номера: приход — та точка, где ячейка появляется
         * как изделие. Без неё паспорт начинался бы с первой проверки
         * или изоляции, а «сколько ячеек оприходовано и когда» осталось
         * бы неизвестным.
         */
        journal.recordReceived(
                productSerialRepository
                        .findDetailsByFullNumbers(batch.fullNumbers()),
                batch,
                batch.getCreatedBy(),
                batch.getConfirmedAt()
        );

        return CellScanningResult.acceptedResult();
    }

    /**
     * Номера прихода в общем реестре.
     *
     * Ячейки получают статус «Выпущен»: на складе они лежат, но ни в
     * один выпуск и ни в одну сборку ещё не включены.
     */
    private void writeSerials(CellReceivingBatch batch) {

        ProductClassifier classifier = batch.getCellClassifier();
        ProductType productType = classifier.getProductType();

        List<ProductSerial> serials = new ArrayList<>(batch.getQuantity());

        for (int serial = batch.getFirstSerial();
             serial <= batch.getLastSerial();
             serial++) {

            ProductSerial item = new ProductSerial();

            item.setProductType(productType);
            item.setSerial(serial);
            item.setProductClassifier(classifier);

            /* Область нумерации датчика — род: без него numbering_key
             * посчитался бы по классификатору. */
            item.setSensorCatalog(
                    productType == ProductType.SENSOR
                            ? classifier.getSensorCatalog()
                            : null
            );

            item.setReceivingBatch(batch);
            item.setStatus(ProductSerialStatus.NEW);
            item.setNote("Приход: " + batch.getSupplierName()
                    + ", ордер " + batch.getIncomingOrderNumber()
                    + ", изм. " + batch.getChangeNumber());

            serials.add(item);
        }

        productSerialRepository.saveAll(serials);
    }

    /**
     * Подсчёт сканов: номер → сколько раз он встретился.
     *
     * Порядок сохранения — порядок сканирования, чтобы перечни
     * проблем оператор видел в том же виде, что и на этикетках.
     */
    private Map<String, Integer> countScanned(CellReceivingBatch batch,
                                              List<String> scannedLines) {

        Map<String, Integer> scanned = new LinkedHashMap<>();

        if (scannedLines == null) {
            return scanned;
        }

        for (String line : scannedLines) {

            String value = normalize(line);

            /*
             * Сканер добавляет пробелы и суффикс Enter, а QR иногда
             * приходит в виде «CELL:121200022»: оставляем только цифры.
             */
            String digits = value.replaceAll("\\D", "");

            if (digits.length() != FULL_NUMBER_LENGTH) {
                /* Нераспознанная строка учитывается отдельным списком. */
                if (!value.isEmpty()) {
                    scanned.merge(value, 1, Integer::sum);
                }

                continue;
            }

            scanned.merge(digits, 1, Integer::sum);
        }

        return scanned;
    }

    /**
     * Готовая надпись о причинах отказа — с перечислением номеров,
     * иначе оператор не найдёт, какую ячейку пересканировать.
     */
    private static String describeProblem(List<String> missing,
                                          List<String> duplicates,
                                          List<String> foreign,
                                          List<String> malformed) {

        StringBuilder message = new StringBuilder(
                "Партия не оприходована: ячейки в реестр не внесены."
        );

        if (!missing.isEmpty()) {
            message.append(" Не отсканировано ")
                    .append(missing.size())
                    .append(" шт.: ")
                    .append(join(missing));
        }

        if (!duplicates.isEmpty()) {
            message.append(" Задвоены: ")
                    .append(join(duplicates));
        }

        if (!foreign.isEmpty()) {
            message.append(" Посторонние номера: ")
                    .append(join(foreign));
        }

        if (!malformed.isEmpty()) {
            message.append(" Не распознано: ")
                    .append(join(malformed));
        }

        return message.toString();
    }

    /**
     * Перечень номеров в сообщении: длинный список обрезаем, иначе
     * плашка растянулась бы на весь экран, а первые позиции видны и
     * в таблице партии.
     */
    private static String join(List<String> numbers) {
        int limit = 12;

        return numbers.size() <= limit
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, limit))
                + " …";
    }

    /**
     * Следующая свободная серия ряда классификатора: максимум реестра
     * и максимум зарезервированных партий, плюс единица.
     */
    private int nextSerial(ProductClassifier classifier) {

        String numberingKey = SerialNumbering.keyFor(classifier);

        Integer registryMax = productSerialRepository
                .findMaxSerialByNumberingKey(numberingKey);

        Integer reservedMax = classifier.getProductType() == ProductType.CELL
                ? batchRepository.findMaxReservedSerial(classifier.getId())
                : null;

        int max = Math.max(
                registryMax == null ? 0 : registryMax,
                reservedMax == null ? 0 : reservedMax
        );

        return max + 1;
    }

    /**
     * Классификатор из формы обязан существовать и быть ячейкой:
     * идентификатор приходит из запроса и может быть передан вручную.
     */
    private ProductClassifier requireCellClassifier(Long id) {

        ProductClassifier classifier = classifierRepository
                .findDetailedById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Название ячейки не выбрано или классификатор не найден"
                ));

        if (classifier.getProductType() != ProductType.CELL) {
            throw new IllegalArgumentException(
                    "Оприходовать можно только ячейку: «"
                            + classifier.getCode()
                            + "» относится к типу «"
                            + classifier.getProductType().getDisplayName()
                            + "»"
            );
        }

        return classifier;
    }

    private static String format(ProductClassifier classifier, int serial) {
        return classifier.getCode() + String.format("%05d", serial);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    /**
     * Поставщик из формы обязан существовать в справочнике:
     * идентификатор приходит из запроса и может быть передан вручную.
     */
    private WarehouseSupplier requireSupplier(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Поставщик не найден"
                ));
    }

    /** Номера партии в порядке печати — список для шага QR. */
    @Transactional(readOnly = true)
    public List<String> fullNumbersOf(Long id) {
        return get(id).fullNumbers();
    }
}
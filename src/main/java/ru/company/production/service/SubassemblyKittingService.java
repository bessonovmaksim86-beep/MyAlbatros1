package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.SubassemblyKittingCellRow;
import ru.company.production.dto.SubassemblyKittingZnpRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Комплектование подсборок в ЗНП: раздел склада полуфабрикатов.
 *
 * Граница раздела — операция самого заказа: в список попадают ЗНП,
 * у которых комплектовочная операция относится к подразделению
 * «Склад полуфабрикатов» и имеет тип «Приёмка на склад» (V54). Ищется
 * именно операция заказа, а не операция техпроцесса: комплектующему
 * маршрут не назначают (см. ZnpKind#requiresTechProcess), поэтому
 * искать приёмку склада в техпроцессе было бы негде.
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

    /** Тип операции приёма на склад (миграция V54). */
    private static final String RECEIVING_TYPE_CODE =
            "RECEIVING";

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
    private final SerialMovementJournalService journal;

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
                        RECEIVING_TYPE_CODE,
                        WAREHOUSE_UNIT_CODE
                );

        if (orders.isEmpty()) {
            return List.of();
        }

        List<ProductSerial> stock = serialRepository
                .findAcceptedOnWarehouse();

        List<SubassemblyKittingCellRow> stockRows = stock.stream()
                .map(serial -> new SubassemblyKittingCellRow(
                        serial.getId(),
                        serial.getFullNumber(),
                        serial.getDisplayName(),
                        serial.getProductClassifier() == null
                                ? null
                                : String.valueOf(
                                        serial.getProductClassifier().getCode()
                                )
                ))
                .toList();

        List<SubassemblyKittingZnpRow> result = new ArrayList<>();

        for (ProductionOrder order : orders) {

            Set<String> taken = linkRepository
                    .findByProductionOrderId(order.getId()).stream()
                    .filter(link -> link.getSerial() != null)
                    .map(link -> link.getSerial().getFullNumber())
                    .collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new
                    ));

            result.add(new SubassemblyKittingZnpRow(
                    order.getId(),
                    order.getNumber(),
                    order.getZnpKindDisplayName(),
                    order.getParentOrderNumber(),
                    operationName(order),
                    order.getFormattedOrderDate(),
                    order.getFormattedDueDate(),
                    order.getStatusDisplayName(),
                    order.getStatusBadgeClass(),
                    order.getNote(),
                    stockRows.stream()
                            .map(row -> row.mark(taken.contains(row.fullNumber())))
                            .toList()
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

        Set<String> taken = linkRepository
                .findByProductionOrderId(orderId).stream()
                .filter(link -> link.getSerial() != null)
                .map(link -> link.getSerial().getFullNumber())
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new
                ));

        List<SubassemblyKittingCellRow> cells = serialRepository
                .findAcceptedOnWarehouse().stream()
                .map(serial -> new SubassemblyKittingCellRow(
                        serial.getId(),
                        serial.getFullNumber(),
                        serial.getDisplayName(),
                        serial.getProductClassifier() == null
                                ? null
                                : String.valueOf(
                                        serial.getProductClassifier().getCode()
                                )
                ))
                .map(row -> row.mark(taken.contains(row.fullNumber())))
                .toList();

        return new SubassemblyKittingView(order, cells, operationName(order));
    }

    /** Заказов, ожидающих комплектования: счётчик пункта меню. */
    @Transactional(readOnly = true)
    public long countPending() {
        return productionOrderRepository.countSubassemblyKittingOrders(
                ProductionOrderStatus.IN_WORK,
                RECEIVING_TYPE_CODE,
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
         */
        List<ProductSerial> stock = serialRepository
                .findAcceptedOnWarehouse();

        List<String> missing = chosen.stream()
                .filter(number -> stock.stream()
                        .noneMatch(s -> number.equals(s.getFullNumber())))
                .toList();

        if (!missing.isEmpty()) {
            return KittingResult.rejected(
                    "Этих ячеек нет на складе полуфабрикатов — принять в ЗНП "
                            + "можно только принятые складом: " + join(missing)
            );
        }

        Operation operation = order.getProductionOperation();

        if (operation == null) {
            throw new IllegalStateException(
                    "В заказе «" + order.getNumber()
                            + "» не задана операция приёмки на склад"
            );
        }

        List<ProductSerial> attached = new ArrayList<>();
        int already = 0;

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
                already++;
                continue;
            }

            SerialProductionOrder link = new SerialProductionOrder();

            link.setSerial(serial);
            link.setProductionOrder(order);
            link.setOperation(operation);
            link.setCreatedBy(executor);
            link.setNote("Комплектование подсборок на складе");

            linkRepository.save(link);
            attached.add(serial);
        }

        if (attached.isEmpty()) {
            return KittingResult.rejected(
                    "Все внесённые ячейки уже входят в заказ «"
                            + order.getNumber() + "»"
            );
        }

        for (ProductSerial serial : attached) {
            journal.recordInProduction(List.of(serial), order, executor);
        }

        return KittingResult.accepted(
                "Внесено в заказ «" + order.getNumber() + "»: "
                        + attached.size() + " яч."
                        + (already > 0
                                ? "; уже вошло ранее " + already + " яч."
                                : "")
        );
    }

    /**
     * Заказ обязан быть комплектующим: выпуск подсборки на склад не
     * принимает, а групповой и ремонтный ЗНП ведутся по своему
     * техпроцессу и операцию приёмки склада не несут.
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

    /** Наименование операции заказа — текст вместо ленивой связи. */
    private static String operationName(ProductionOrder order) {

        Operation operation = order.getProductionOperation();

        if (operation == null || operation.getOperationName() == null) {
            return "—";
        }

        return operation.getOperationName().getName();
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

        /** Вносить нечего: весь складской перечень уже в заказе. */
        public boolean nothingToAdd() {
            return availableCount() > 0 && takenCount() >= availableCount();
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
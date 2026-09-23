package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.RepairOrderForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.InputControlDecision;
import ru.company.production.entity.InputControlDefectGroup;
import ru.company.production.entity.InputControlDefectReason;
import ru.company.production.entity.InputControlStatus;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.RepairOrder;
import ru.company.production.entity.RepairOrderItem;
import ru.company.production.entity.RepairOrderStatus;
import ru.company.production.entity.RepairOrderType;
import ru.company.production.entity.WarehouseSupplier;
import ru.company.production.repository.InputControlDefectGroupRepository;
import ru.company.production.repository.RepairOrderItemRepository;
import ru.company.production.repository.RepairOrderRepository;
import ru.company.production.repository.WarehouseSupplierRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Служба качества: разбор изолятора брака и заказы на ремонт (ЗНП).
 *
 * Руководитель службы разбирает изолятор не партиями, а ячейками:
 * отбирает их по разновидности (подсборка / ячейка), классификатору и
 * типу брака, отмечает нужные и оформляет на них заказ — на ремонт
 * ячеек, на ремонт подсборок или на выдачу комплектации.
 *
 * Перечень изолятора читают через {@link InputControlService}: тот уже
 * собирает строки брака (номера, цепочку причин, реквизиты партии) для
 * реестра БВК, и различаются разделы только отбором и назначением
 * строк. Здесь поверх тех же строк строят построчный список ячеек —
 * именно ячейку руководитель отмечает и именно она становится строкой
 * заказа, тогда как карточка БВК остаётся групповой.
 *
 * Заказы на ремонт живут отдельно от {@code ProductionOrder}: у них нет
 * заказа покупателя и состава-выпуска, зато есть поставщик и перечень
 * конкретных номеров (см. миграцию V34).
 */
@Service
@RequiredArgsConstructor
public class QualityService {

    /** Префикс номера заказа: ЗНР-ГГГГ-NNN. */
    private static final String NUMBER_PREFIX = "ЗНР-";

    /** Шаблон номера для автогенерации: год и порядковый номер. */
    private static final Pattern NUMBERED =
            Pattern.compile("ЗНР-(\\d{4})-(\\d+)");

    /** Разделитель пары «id группы | полный номер» в форме. */
    private static final String CELL_SEPARATOR = "\\|";

    private final InputControlService inputControlService;
    private final InputControlDefectGroupRepository groupRepository;
    private final RepairOrderRepository repairOrderRepository;
    private final RepairOrderItemRepository repairItemRepository;
    private final WarehouseSupplierRepository supplierRepository;

    /* ============================================================
       ПЕРЕЧЕНЬ ИЗОЛЯТОРА ДЛЯ РАЗБОРА
       ============================================================ */

    /**
     * Ячейки изолятора с отбором и пометкой «уже в заказе».
     *
     * Группы изолятора разворачивают в построчный список ячеек: у
     * группы с отметкой «вся партия» состав берут полным диапазоном
     * партии, у остальных — внесёнными номерами. Так перечень отдаёт
     * ровно те строки, которые руководитель отмечает, и форма заказа
     * получает пару «группа | номер» без повторного обращения к базе.
     *
     * Номер, по которому уже выставлен незакрытый заказ, помечают:
     * иначе руководитель повторно оформил бы ремонт на ту же ячейку.
     */
    @Transactional(readOnly = true)
    public List<QuarantineCellRow> findCells(QuarantineFilter filter) {
        List<InputControlService.DefectRecordView> records =
                inputControlService.findQuarantine(filter);

        if (records.isEmpty()) {
            return List.of();
        }

        List<String> numbers = records.stream()
                .flatMap(record -> cellNumbers(record).stream())
                .distinct()
                .toList();

        Set<String> ordered = numbers.isEmpty()
                ? Set.of()
                : new LinkedHashSet<>(
                        repairItemRepository.findNumbersInOpenOrdersByNumbers(numbers)
                );

        List<QuarantineCellRow> rows = new ArrayList<>();

        for (InputControlService.DefectRecordView record : records) {
            for (String number : cellNumbers(record)) {
                rows.add(new QuarantineCellRow(
                        record.group().getId(),
                        number,
                        record.getKind(),
                        record.batch(),
                        record.getReasonChainLabel(),
                        record.card().getCheckerName(),
                        record.card().getFormattedCompletedAt(),
                        ordered.contains(number)
                ));
            }
        }

        return rows;
    }

    /**
     * Отмеченные руководителем ячейки для формы нового заказа.
     *
     * Форма заказа — второй шаг разбора: перечень изолятора отдаёт
     * пары «id группы | номер», а страница заказа обязана показать те
     * же ячейки с наименованием, поставщиком и причиной, иначе
     * руководитель выбирал бы вид заказа и поставщика вслепую.
     *
     * Состав берут из того же перечня без отбора и отсекают по ключам:
     * пара «группа | номер» уникальна, поэтому повторная отметка в
     * форме не удваивает строку. Отмеченный ключ, которого в изоляторе
     * нет, — признак устаревшей страницы (партию разобрали другой
     * проверкой), и заказ по такому составу не оформляют.
     */
    @Transactional(readOnly = true)
    public List<QuarantineCellRow> findPickedCells(List<String> cellKeys) {
        if (cellKeys == null || cellKeys.isEmpty()) {
            return List.of();
        }

        Set<String> wanted = new LinkedHashSet<>();

        for (String key : cellKeys) {
            if (key != null && !key.isBlank()) {
                wanted.add(key.trim());
            }
        }

        if (wanted.isEmpty()) {
            return List.of();
        }

        List<QuarantineCellRow> picked = findCells(QuarantineFilter.empty())
                .stream()
                .filter(row -> wanted.contains(row.getCellKey()))
                .toList();

        if (picked.size() != wanted.size()) {
            throw new IllegalStateException(
                    "Часть отмеченных ячеек уже не лежит в изоляторе "
                            + "брака: обновите перечень"
            );
        }

        return picked;
    }

    /**
     * Подсказка вида заказа по составу отмеченных ячеек.
     *
     * Однородный состав предопределяет назначение заказа, и форму
     * открывают уже с выбранным вариантом; смешанный состав вида не
     * подсказывает — тогда вид выбирают вручную.
     */
    public static RepairOrderType suggestOrderType(List<QuarantineCellRow> rows) {
        return RepairOrderType.suggestFrom(
                rows == null
                        ? List.of()
                        : rows.stream().map(QuarantineCellRow::kind).toList()
        );
    }

    /** Классификаторы, чьи ячейки есть в изоляторе: опции фильтра. */
    @Transactional(readOnly = true)
    public List<InputControlDefectGroupRepository.QuarantineClassifierOption>
    findClassifierOptions() {
        return groupRepository.findQuarantineClassifierOptions();
    }

    /** Причины брака первого уровня: опции фильтра по типу брака. */
    @Transactional(readOnly = true)
    public List<InputControlDefectReason> findReasonOptions() {
        return inputControlService.findQuarantineReasonOptions();
    }

    /**
     * Полный перечень номеров группы.
     *
     * Группа «вся партия» не хранит номера поштучно — её состав равен
     * диапазону партии, и перечень обязан показать все ячейки, иначе
     * отмечать было бы нечего.
     */
    private List<String> cellNumbers(
            InputControlService.DefectRecordView record) {

        if (record.group().isDefectAll()) {
            CellReceivingBatch batch = record.batch();
            return batch == null ? List.of() : batch.fullNumbers();
        }

        return record.numbers();
    }

    /* ============================================================
       ЗАКАЗЫ НА РЕМОНТ
       ============================================================ */

    @Transactional(readOnly = true)
    public List<RepairOrder> findOrders() {
        return repairOrderRepository.findAllDetailed();
    }

    @Transactional(readOnly = true)
    public RepairOrder getOrder(Long id) {
        return repairOrderRepository.findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ на ремонт не найден"
                ));
    }

    /** Состав заказа: отмеченные ячейки с наименованиями. */
    @Transactional(readOnly = true)
    public List<RepairOrderItem> findOrderItems(Long orderId) {
        return repairItemRepository.findDetailsByOrderId(orderId);
    }

    /** Незакрытых заказов: сводка модуля и счётчик меню. */
    @Transactional(readOnly = true)
    public long countOpenOrders() {
        return repairOrderRepository.countOpen();
    }

    /** Заказов в работе — отдельная цифра сводки. */
    @Transactional(readOnly = true)
    public long countInWorkOrders() {
        return repairOrderRepository.countByStatus(RepairOrderStatus.IN_WORK);
    }

    /** Выполненных заказов — сводка службы. */
    @Transactional(readOnly = true)
    public long countDoneOrders() {
        return repairOrderRepository.countByStatus(RepairOrderStatus.DONE);
    }

    /** Поставщики справочника склада: выбор в форме заказа. */
    @Transactional(readOnly = true)
    public List<WarehouseSupplier> findSuppliers() {
        return supplierRepository.findAllByOrderByNameAsc();
    }

    /**
     * Ближайший свободный номер заказа вида ЗНР-ГГГГ-NNN.
     *
     * Ряд ведут по году, как номера заказов покупателя: внутри года
     * берут максимальный порядковый и добавляют единицу. Отменённые
     * заказы входят в перечень — номер обязан быть свободен для сверки
     * бумажных документов.
     */
    @Transactional(readOnly = true)
    public String nextNumber() {
        int year = LocalDate.now().getYear();

        int max = 0;

        for (String number : repairOrderRepository.findNumbersByPrefix(
                NUMBER_PREFIX + year)) {

            if (number == null) {
                continue;
            }

            Matcher matcher = NUMBERED.matcher(number.trim());

            if (matcher.matches()) {
                try {
                    max = Math.max(max, Integer.parseInt(matcher.group(2)));
                } catch (NumberFormatException ignored) {
                    /*
                     * Номера, введённые вручную мимо шаблона, в
                     * автогенерации не участвуют.
                     */
                }
            }
        }

        return NUMBER_PREFIX + year + "-" + String.format("%03d", max + 1);
    }

    /**
     * Создаёт заказ на ремонт из отмеченных ячеек изолятора.
     *
     * Каждая ячейка обязана ссылаться на конкретную группу брака — тот
     * же номер мог быть забракован в разных группах с разными
     * причинами. Дубликаты номеров убирают до вставки: на заказ номер
     * приходится один раз (ограничение {@code uk_repair_order_item_number}),
     * и повторный номер в форме — это повторная отметка, а не состав.
     */
    @Transactional
    public RepairOrder create(RepairOrderForm form, AppUser author) {
        RepairOrderType orderType = parseOrderType(form.getOrderType());

        List<PickedCell> picked = parseCells(form.getCells());

        if (picked.isEmpty()) {
            throw new IllegalArgumentException(
                    "Отметьте в изоляторе хотя бы одну ячейку"
            );
        }

        WarehouseSupplier supplier = resolveSupplier(form);
        String supplierName = resolveSupplierName(form, supplier);

        RepairOrder order = new RepairOrder();
        order.setNumber(nextNumber());
        order.setOrderType(orderType);
        order.setStatus(RepairOrderStatus.NEW);
        order.setOrderDate(LocalDate.now());
        order.setSupplier(supplier);
        order.setSupplierName(supplierName);
        order.setNote(trimToNull(form.getNote()));
        order.setCreatedBy(author);

        int position = 1;

        for (PickedCell cell : picked) {
            RepairOrderItem item = new RepairOrderItem();
            item.setDefectGroup(cell.group());
            item.setProductClassifier(classifierOf(cell.group()));
            item.setFullNumber(cell.fullNumber());
            item.setCellKind(kindOf(cell.group()));
            item.setPosition(position++);
            order.addItem(item);
        }

        return repairOrderRepository.save(order);
    }

    /** Берёт заказ в работу. */
    @Transactional
    public RepairOrder start(Long id) {
        RepairOrder order = getOrder(id);

        if (order.isClosed()) {
            throw new IllegalStateException(
                    "Закрытый заказ «" + order.getNumber()
                            + "» нельзя взять в работу"
            );
        }

        order.setStatus(RepairOrderStatus.IN_WORK);

        return repairOrderRepository.save(order);
    }

    /** Закрывает заказ как выполненный. */
    @Transactional
    public RepairOrder complete(Long id) {
        RepairOrder order = getOrder(id);

        if (order.isClosed()) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber() + "» уже закрыт"
            );
        }

        order.setStatus(RepairOrderStatus.DONE);

        return repairOrderRepository.save(order);
    }

    /** Отменяет заказ: ячейки возвращаются в изолятор. */
    @Transactional
    public RepairOrder cancel(Long id) {
        RepairOrder order = getOrder(id);

        if (order.getStatus() == RepairOrderStatus.DONE) {
            throw new IllegalStateException(
                    "Выполненный заказ «" + order.getNumber()
                            + "» отменить нельзя"
            );
        }

        order.setStatus(RepairOrderStatus.CANCELLED);

        return repairOrderRepository.save(order);
    }

    /* ============================================================
       РАЗБОР ФОРМЫ ЗАКАЗА
       ============================================================ */

    /** Отмеченная ячейка: группа-источник и её полный номер. */
    private record PickedCell(InputControlDefectGroup group, String fullNumber) {
    }

    /**
     * Разбирает строки «groupId|number» в ячейки с загруженными
     * группами.
     *
     * Группу читают и проверяют здесь: она обязана относиться к
     * изолятору брака, иначе заказ ссылался бы на возврат поставщику.
     * Повторные номера пропускают — в заказ номер входит один раз.
     */
    private List<PickedCell> parseCells(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }

        List<PickedCell> picked = new ArrayList<>();
        Set<String> seenNumbers = new LinkedHashSet<>();

        for (String raw : cells) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            String[] parts = raw.split(CELL_SEPARATOR, 2);

            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Некорректная отметка ячейки: «" + raw.trim() + "»"
                );
            }

            Long groupId = parseGroupId(parts[0]);
            String fullNumber = parts[1].trim();

            if (fullNumber.isEmpty()) {
                throw new IllegalArgumentException(
                        "Отметка ячейки без номера"
                );
            }

            if (!seenNumbers.add(fullNumber)) {
                continue;
            }

            picked.add(new PickedCell(getQuarantineGroup(groupId), fullNumber));
        }

        return picked;
    }

    private Long parseGroupId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Некорректный идентификатор группы брака"
            );
        }
    }

    /**
     * Группа изолятора брака по идентификатору.
     *
     * Проверяют и решение, и закрытость карточки: в заказ берут только
     * ячейки, оставленные в изоляторе по завершённой проверке, — иначе
     * форма могла бы сослаться на группу возврата или на незакрытую
     * проверку, состав которой ещё меняется.
     */
    private InputControlDefectGroup getQuarantineGroup(Long groupId) {
        InputControlDefectGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Группа брака не найдена"
                ));

        boolean quarantined =
                group.getDecision() == InputControlDecision.QUARANTINE
                        && group.getCard().getStatus()
                        == InputControlStatus.COMPLETED;

        if (!quarantined) {
            throw new IllegalArgumentException(
                    "Ячейка не относится к изолятору брака"
            );
        }

        return group;
    }

    private ProductClassifier classifierOf(InputControlDefectGroup group) {
        CellReceivingBatch batch = group.getCard().getBatch();
        return batch == null ? null : batch.getCellClassifier();
    }

    private QuarantineCellKind kindOf(InputControlDefectGroup group) {
        CellReceivingBatch batch = group.getCard().getBatch();
        return QuarantineCellKind.ofAccepted(
                batch != null && batch.getAcceptedAt() != null
        );
    }

    private RepairOrderType parseOrderType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Выберите вид заказа на ремонт"
            );
        }

        try {
            return RepairOrderType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Неизвестный вид заказа"
            );
        }
    }

    /**
     * Поставщик заказа из справочника склада.
     *
     * Совпадение по справочнику фиксируют ссылкой, чтобы сводка
     * собиралась по поставщику; «другое» написание оставляют текстом
     * без ссылки.
     */
    private WarehouseSupplier resolveSupplier(RepairOrderForm form) {
        if (form.isCustomSupplier()) {
            return null;
        }

        if (form.getSupplierId() == null) {
            throw new IllegalArgumentException(
                    "Выберите поставщика или отметьте «другой»"
            );
        }

        return supplierRepository.findById(form.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Поставщик не найден в справочнике склада"
                ));
    }

    /**
     * Написание поставщика: из справочника либо вписанное вручную.
     *
     * Ссылка на справочник не заменяет написание — заказ печатают по
     * тексту, а ссылка нужна только сводке.
     */
    private String resolveSupplierName(RepairOrderForm form, WarehouseSupplier supplier) {
        if (supplier != null) {
            return supplier.getName().trim();
        }

        String typed = trimToNull(form.getSupplierName());

        if (typed == null) {
            throw new IllegalArgumentException(
                    "Укажите наименование поставщика"
            );
        }

        return typed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Строка перечня изолятора: одна отмеченная ячейка.
     *
     * Пару «id группы | номер» перечня форма заказа отправляет как
     * есть, поэтому строка держит оба значения целиком и не требует
     * от шаблона читать ленивые связи (open-in-view выключен).
     */
    public record QuarantineCellRow(
            Long groupId,
            String fullNumber,
            QuarantineCellKind kind,
            CellReceivingBatch batch,
            String reasonChain,
            String checkerName,
            String completedLabel,
            boolean alreadyOrdered
    ) {

        /** Значение чекбокса: «groupId|номер» для формы заказа. */
        public String getCellKey() {
            return groupId + "|" + fullNumber;
        }

        public String getKindLabel() {
            return kind == null ? "—" : kind.getDisplayName();
        }

        public String getSupplierName() {
            return batch == null ? "—" : batch.getSupplierName();
        }

        public String getIncomingOrderNumber() {
            return batch == null ? "—" : batch.getIncomingOrderNumber();
        }

        public String getCellLabel() {
            return batch == null ? fullNumber : batch.getCellLabel();
        }
    }
}

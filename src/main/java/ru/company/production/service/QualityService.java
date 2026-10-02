package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.QuarantineReasonGroup;
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
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.WarehouseSupplier;
import ru.company.production.repository.InputControlDefectGroupRepository;
import ru.company.production.repository.RepairOrderItemRepository;
import ru.company.production.repository.RepairOrderRepository;
import ru.company.production.repository.RepairRouteOptionView;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.WarehouseSupplierRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Разделитель ступеней цепочки причин («Тип → вид»). */
    private static final String REASON_SEPARATOR = "→";

    /** Строк в перечне сводки: обзор читают с одного экрана. */
    private static final int REASON_ROWS = 5;

    private static final int SUPPLIER_ROWS = 5;

    /** Ячеек в перечне «последние» на обзоре. */
    private static final int RECENT_ROWS = 8;

    /**
     * Исполнитель заказа, который остаётся на предприятии.
     *
     * Колонка supplier_name обязательна (заказ печатают по тексту), а
     * поставщика у такого заказа нет — в неё пишут наименование
     * исполнителя, чтобы печатная форма не оставалась с пустой строкой.
     */
    private static final String SDP_EXECUTOR_LABEL = "Силами СДП";

    private final InputControlService inputControlService;
    private final InputControlDefectGroupRepository groupRepository;
    private final RepairOrderRepository repairOrderRepository;
    private final RepairOrderItemRepository repairItemRepository;
    private final WarehouseSupplierRepository supplierRepository;
    private final TechProcessRepository techProcessRepository;

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

        requireHomogeneous(picked);

        return picked;
    }

    /**
     * Состав заказа обязан быть однородным: одна разновидность и одно
     * наименование изделия.
     *
     * Проверяют здесь, а не только при записи, — смешанный состав
     * выявляется по отмеченным ячейкам и виден сразу: пользователю
     * незачем проходить всю форму, чтобы узнать в конце, что заказ не
     * оформят.
     *
     * Разновидности и наименования запрещают смешивать по разным
     * причинам. Ячейку и подсборку ремонтируют разные участки и закрывают
     * разными актами, поэтому такой ЗНП распался бы на два документа.
     * Разные наименования в одном заказе оставляют исполнителя без
     * ответа, к какому изделию относиться составу: ЗНП печатают с одной
     * строкой изделия.
     */
    private static void requireHomogeneous(List<QuarantineCellRow> rows) {
        Set<QuarantineCellKind> kinds = new LinkedHashSet<>();
        Map<Long, String> classifiers = new LinkedHashMap<>();

        for (QuarantineCellRow row : rows) {
            kinds.add(row.kind());

            Long classifierId = row.getClassifierId();

            if (classifierId != null) {
                classifiers.putIfAbsent(classifierId, row.getCellLabel());
            }
        }

        if (kinds.size() > 1) {
            throw new IllegalStateException(
                    "В одном заказе нельзя смешивать ячейки и подсборки:"
                            + " оформите два заказа — на ремонт ячеек и"
                            + " на ремонт подсборок"
            );
        }

        if (classifiers.size() > 1) {
            throw new IllegalStateException(
                    "В одном заказе нельзя смешивать разные наименования"
                            + " ячеек и подсборок. Отмечено изделий: "
                            + String.join(", ", classifiers.values())
                            + ". Оформите заказ на каждое наименование"
                            + " отдельно"
            );
        }
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
     * Типы брака с их видами: опции фильтра, готовые к отрисовке.
     *
     * Группу собирает {@link QuarantineReasonGroup}: перечень изолятора
     * фильтруют и бюро входного контроля, и служба качества, а дерево
     * причин одно на обоих — два набора подписей одного справочника
     * читались бы как два разных отбора.
     */
    @Transactional(readOnly = true)
    public List<QuarantineReasonGroup> findReasonGroups() {
        return QuarantineReasonGroup.of(findReasonOptions());
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
     * Состав заказа держат однородным, и правила эти берут из бумаги,
     * а не из удобства формы:
     *
     * - в одном ЗНП не смешивают ячейки и подсборки — их ремонтируют
     *   разные участки и закрывают разными актами;
     * - в одном ЗНП не смешивают разные наименования изделий: заказ
     *   оформляют на партию одинаковых ячеек, иначе исполнитель не
     *   поймёт, к какому изделию относиться к составу;
     * - вид заказа ставят по составу, а не по выбору пользователя:
     *   раз состав однородный, вид выводится из него однозначно, и
     *   ручная выборка только расходилась бы с перечнем.
     *
     * Номер берёт тот, что ввёл руководитель по журналу подразделения;
     * пустое поле означает «подобрать ближайший свободный ряда года».
     *
     * Исполнитель — один из двух: поставщик (заказ уходит наружу) или
     * собственные силы, СДП (заказ остаётся на предприятии и получает
     * маршрут ремонта). Оба способа взаимоисключающи, поэтому при СДП
     * поставщика не требуют и не пишут.
     */
    @Transactional
    public RepairOrder create(RepairOrderForm form, AppUser author) {
        List<PickedCell> picked = parseCells(form.getCells());

        if (picked.isEmpty()) {
            throw new IllegalArgumentException(
                    "Отметьте в изоляторе хотя бы одну ячейку"
            );
        }

        RepairOrderType orderType = resolveOrderType(picked);

        TechProcess route = null;
        WarehouseSupplier supplier = null;
        String supplierName;

        if (form.isBySdp()) {
            route = resolveTechProcess(form);
            supplierName = SDP_EXECUTOR_LABEL;
        } else {
            supplier = resolveSupplier(form);
            supplierName = resolveSupplierName(form, supplier);
        }

        boolean kitsIssue = form.isKitsIssue();
        String kitsList = trimToNull(form.getKitsList());

        if (kitsIssue && kitsList == null) {
            throw new IllegalArgumentException(
                    "Укажите перечень компонентов для выдачи"
            );
        }

        RepairOrder order = new RepairOrder();
        order.setNumber(resolveNumber(form.getNumber()));
        order.setOrderType(orderType);
        order.setStatus(RepairOrderStatus.NEW);
        order.setOrderDate(LocalDate.now());
        order.setSupplier(supplier);
        order.setSupplierName(supplierName);
        order.setBySdp(form.isBySdp());
        order.setTechProcess(route);
        order.setKitsIssue(kitsIssue);
        order.setKitsList(kitsIssue ? kitsList : null);
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

    /**
     * Вид заказа по составу отмеченных ячеек.
     *
     * Здесь же держат запрет на смешение разновидностей: однородность
     * состава — то же самое свойство, что и однозначный вид. Если в
     * заказе и ячейки, и подсборки, вид подсказать нечем, и заказ
     * пришлось бы печатать с двумя разными основаниями в одной строке.
     */
    private RepairOrderType resolveOrderType(List<PickedCell> picked) {
        Set<QuarantineCellKind> kinds = new LinkedHashSet<>();

        for (PickedCell cell : picked) {
            kinds.add(kindOf(cell.group()));
        }

        if (kinds.size() > 1) {
            throw new IllegalArgumentException(
                    "В одном заказе нельзя смешивать ячейки и подсборки:"
                            + " оформите два заказа — на ремонт ячеек и"
                            + " на ремонт подсборок"
            );
        }

        RepairOrderType suggested = RepairOrderType.suggestFrom(kinds);

        if (suggested == null) {
            throw new IllegalArgumentException(
                    "Не удалось определить вид заказа по отмеченным ячейкам"
            );
        }

        return suggested;
    }

    /**
     * Номер заказа: вписанный руководителем либо подобранный сервисом.
     *
     * Вписанный сверяют с занятыми без учёта регистра: журнал ведения
     * ЗНП не различает «знр-2026-007» и «ЗНР-2026-007», а два бумажных
     * документа с одним номером читают как один.
     */
    private String resolveNumber(String typed) {
        String number = trimToNull(typed);

        if (number == null) {
            return nextNumber();
        }

        if (repairOrderRepository.existsByNumberIgnoreCase(number)) {
            throw new IllegalArgumentException(
                    "ЗНП с номером «" + number + "» уже оформлен"
            );
        }

        return number;
    }

    /**
     * Маршрут ремонта для заказа силами СДП.
     *
     * Для собственного ремонта маршрут обязателен: без него исполнитель
     * не знает, по каким операциям выполняют работу, и заказ остаётся
     * перечнем номеров.
     */
    private TechProcess resolveTechProcess(RepairOrderForm form) {
        Long id = form.getTechProcessId();

        if (id == null) {
            throw new IllegalArgumentException(
                    "Для ремонта силами СДП выберите техпроцесс на ремонт"
            );
        }

        return techProcessRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Техпроцесс на ремонт не найден в справочнике"
                ));
    }

    /**
     * Маршруты для выбора в форме: активные техпроцессы справочника.
     *
     * Отдают проектными строками — форме нужны код маршрута и изделие,
     * а операции маршрута тянули бы ленивые коллекции.
     *
     * Точка замены на отдельный справочник: сейчас список берут из
     * общего реестра техпроцессов, потому что справочника «техпроцессы
     * на ремонт» в системе нет. Когда его заведут, достаточно заменить
     * источник этого запроса — форма и заказ останутся прежними.
     */
    @Transactional(readOnly = true)
    public List<RepairRouteOptionView> findRouteOptions() {
        return techProcessRepository.findRepairRouteOptions();
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
                    "Выберите поставщика из справочника или укажите "
                            + "«другой поставщик» с наименованием"
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

        /** Наименование изделия без обращения к ленивой связи. */
        public String getCellLabel() {
            return batch == null ? fullNumber : batch.getCellLabel();
        }

        /**
         * Идентификатор наименования ячейки: по нему сверяют однородность
         * состава заказа.
         *
         * Классификатор берут из партии — связь открыта fetch'ем запроса
         * изолятора (см. getCellLabel), поэтому сравнение работает и при
         * выключенном open-in-view. Пустой идентификатор означает, что у
         * партии изделие не заполнено; такие строки однородность не
         * нарушают, иначе перечень отказывал бы на неполных данных.
         */
        public Long getClassifierId() {
            if (batch == null || batch.getCellClassifier() == null) {
                return null;
            }

            return batch.getCellClassifier().getId();
        }

        /*
         * Двойники вида getX() держат рядом с аксессорами record
         * намеренно: Thymeleaf читает свойства через JavaBeans-соглашение
         * (get<Имя>() / is<Имя>()), а record компилируется с аксессором
         * <имя>() без префикса. Без этих методов строка перечня
         * отрисовалась бы пустой или страница упала бы на
         * PropertyOrFieldNotAvailableException.
         */
        public Long getGroupId() {
            return groupId;
        }

        public String getFullNumber() {
            return fullNumber;
        }

        public QuarantineCellKind getKind() {
            return kind;
        }

        public CellReceivingBatch getBatch() {
            return batch;
        }

        public String getReasonChain() {
            return reasonChain;
        }

        public String getCheckerName() {
            return checkerName;
        }

        public String getCompletedLabel() {
            return completedLabel;
        }

        public boolean isAlreadyOrdered() {
            return alreadyOrdered;
        }
    }

    /**
     * Сводка службы качества: из чего сейчас состоит изолятор брака.
     *
     * Обзор строят по тем же строкам перечня, что видит руководитель в
     * разборе, а не отдельными запросами: иначе цифра на обзоре и цифра
     * в перечне разошлись бы при первой же правке отбора. Счётчики
     * считают здесь, внутри транзакции, — {@code open-in-view} выключен,
     * и сводить строки по поставщикам и причинам в шаблоне означало бы
     * обращаться к ленивым связкам уже при отрисовке.
     *
     * Тип брака берут первым звеном цепочки: в изоляторе ячейку ведут к
     * виду («Скол корпуса»), а обзор отвечает на вопрос, каким типом
     * брака живёт изолятор сегодня.
     */
    @Transactional(readOnly = true)
    public Overview overview() {
        List<QuarantineCellRow> cells = findCells(QuarantineFilter.empty());

        int subassemblies = 0;
        int ordered = 0;

        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        Map<String, Integer> supplierCounts = new LinkedHashMap<>();
        Set<String> classifiers = new LinkedHashSet<>();

        for (QuarantineCellRow cell : cells) {
            if (cell.kind() == QuarantineCellKind.SUBASSEMBLY) {
                subassemblies++;
            }

            if (cell.alreadyOrdered()) {
                ordered++;
            }

            String classifier = cell.getCellLabel();

            if (classifier != null && !classifier.isBlank()) {
                classifiers.add(classifier);
            }

            count(reasonCounts, topLevelReason(cell.reasonChain()));
            count(supplierCounts, cell.getSupplierName());
        }

        return new Overview(
                cells.size(),
                subassemblies,
                cells.size() - subassemblies,
                ordered,
                cells.size() - ordered,
                classifiers.size(),
                topEntries(reasonCounts, REASON_ROWS),
                topEntries(supplierCounts, SUPPLIER_ROWS),
                cells.size() > RECENT_ROWS
                        ? List.copyOf(cells.subList(0, RECENT_ROWS))
                        : cells
        );
    }

    /** Метка верхнего уровня цепочки причин («Тип → вид» → «Тип»). */
    private static String topLevelReason(String chain) {
        if (chain == null || chain.isBlank()) {
            return "Причина не указана";
        }

        int arrow = chain.indexOf(REASON_SEPARATOR);

        String top = (arrow > 0 ? chain.substring(0, arrow) : chain).trim();

        return top.isEmpty() ? "Причина не указана" : top;
    }

    private static void count(Map<String, Integer> counter, String key) {
        counter.merge(key == null ? "—" : key, 1, Integer::sum);
    }

    /**
     * Первые {@code limit} написаний по числу ячеек.
     *
     * Порядок — по убыванию счётчика, затем по написанию: перечень
     * должен читаться как «что чаще всего лежит в изоляторе», а не как
     * случайная выборка из хеш-контейнера.
     */
    private static List<CountEntry> topEntries(
            Map<String, Integer> counter,
            int limit) {

        return counter.entrySet()
                .stream()
                .sorted(
                        Comparator
                                .comparing(
                                        (Map.Entry<String, Integer> entry) ->
                                                entry.getValue())
                                .reversed()
                                .thenComparing(Map.Entry::getKey)
                )
                .limit(limit)
                .map(entry -> new CountEntry(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Строка сводки: написание и число ячеек. */
    public record CountEntry(String label, int count) {

        public String getLabel() {
            return label;
        }

        public int getCount() {
            return count;
        }
    }

    /**
     * Сводка изолятора брака для обзора службы.
     *
     * Все значения уже пересчитаны в числа и строки: шаблон только
     * выводит их и не сводит перечень повторно.
     */
    public record Overview(
            int cellCount,
            int subassemblyCount,
            int cellKindCount,
            int orderedCount,
            int freeCount,
            int classifierCount,
            List<CountEntry> reasons,
            List<CountEntry> suppliers,
            List<QuarantineCellRow> recentCells
    ) {

        public int getCellCount() {
            return cellCount;
        }

        public int getSubassemblyCount() {
            return subassemblyCount;
        }

        public int getCellKindCount() {
            return cellKindCount;
        }

        public int getOrderedCount() {
            return orderedCount;
        }

        public int getFreeCount() {
            return freeCount;
        }

        public int getClassifierCount() {
            return classifierCount;
        }

        public List<CountEntry> getReasons() {
            return reasons;
        }

        public List<CountEntry> getSuppliers() {
            return suppliers;
        }

        public List<QuarantineCellRow> getRecentCells() {
            return recentCells;
        }
    }

}

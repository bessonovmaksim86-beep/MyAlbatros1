package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ProductionOrderForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderItem;
import ru.company.production.entity.ProductionOrderStatus;      
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ClassifierRouteOptionView;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.TechProcessOperationRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Логика заказов на производство диспетчерского отдела.
 *
 * Заказ на производство — это выпуск конкретных экземпляров изделий:
 * каждая позиция получает серийный номер вида
 *
 *     121200022 = 1212 (код классификатора) + 00022 (серия по типу)
 *
 * Состав выпуска не вводится вручную, а выводится из позиций заказа
 * покупателя: сколько экземпляров заявлено в позиции, столько строк
 * выпуска создаётся, и каждая строка сразу получает свой номер.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductSerialRepository productSerialRepository;
    private final ProductSerialOperationRepository
            productSerialOperationRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final OperationRepository operationRepository;
    private final ProductClassifierRepository productClassifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final TechProcessClassifierRepository techProcessClassifierRepository;

    private final TechProcessOperationRepository
            techProcessOperationRepository;

    /*
     * График заказов покупателя — источник признака «поставлен в
     * планирование». Проверка при взятии ЗНП в работу сверяется именно с
     * ним: плановая дата живёт в строке графика (order_schedule_rows), а
     * не у цеховой операции изделия, которая появляется только после
     * выпуска номера.
     */
    private final OrderScheduleRowRepository orderScheduleRowRepository;

    /*
     * Расчёт графика по техпроцессу самого заказа (V56). Нужен именно
     * заказу на производство: у группового и ремонтного ЗНП нет заказа
     * покупателя, и планировщик, который строит план от позиций заказа
     * покупателя, их не обслуживает.
     *
     * Зависимость односторонняя: планировщик больше не зовёт этот сервис
     * (расчёт числа экземпляров стал статическим instanceCount), иначе
     * создался бы круг, который Spring отвергает по умолчанию.
     */
    private final OrderSchedulingService orderSchedulingService;

    /*
     * Комплектование ЗНП: привязка заказа к уже выпущенным серийникам.
     * Вынесена в отдельный сервис, потому что связь «датчик ⇄ заказ»
     * «многие ко многим» живёт своей таблицей (V20) и нужна и карточке
     * изделия, и карточке заказа.
     */
    private final SerialKittingService serialKittingService;

    /*
     * Переиспользование резерва: claimForRelease — единственный способ
     * отдать выпуску уже зарезерированный заказом номер, не выдавая новый.
     * Ряд изделия считает один сервис, и второй расчёт выдал бы занятую
     * серию, а уникальный ключ (numbering_key, serial) поймал бы это уже
     * как нарушение целостности.
     */
    private final SerialIssueService serialIssueService;

    /*
     * Нужен проверке отмеченного номера: свободный по выпуску серийник
     * уже может быть укомплектован ЗНП, и выпускать его заново нельзя.
     */
    private final SerialProductionOrderRepository
            serialProductionOrderRepository;

    private static final String NUMBER_PREFIX = "ЗНП-";

    private static final Pattern NUMBERED =
            Pattern.compile("^ЗНП-(\\d{4})-(\\d+)$");

    /* Верхняя граница пятизначной серии (chk_product_serial_range). */
    private static final int MAX_SERIAL = 99999;

    /**
     * Ближайший свободный номер заказа вида ЗНП-ГГГГ-NNN,
     * где ГГГГ — текущий год.
     */
    public String nextNumber() {
        int year = LocalDate.now().getYear();

        int max = 0;

        for (String number : productionOrderRepository.findAllNumbers()) {
            if (number == null) {
                continue;
            }

            Matcher matcher = NUMBERED.matcher(number.trim());

            if (matcher.matches()
                    && Integer.parseInt(matcher.group(1)) == year) {
                try {
                    max = Math.max(max, Integer.parseInt(matcher.group(2)));
                } catch (NumberFormatException ignored) {
                    /*
                     * Номера, введённые вручную и не подходящие
                     * под шаблон, в автогенерации не участвуют.
                     */
                }
            }
        }

        int next = max + 1;

        return NUMBER_PREFIX + year + "-"
                + String.format(
                        "%0" + Math.max(3, String.valueOf(next).length()) + "d",
                        next
                );
    }

    /**
     * Комплектовочные операции — единственный допустимый выбор
     * для заказа на производство.
     */
    public List<Operation> findKittingOperations() {
        return operationRepository.findByOperationTypeCode(
                OperationTypeCode.KITTING.name()
        );
    }

    /**
     * Комплектовочные операции изделия конкретного серийного номера.
     *
     * Операции берутся из базового техпроцесса классификатора серийника,
     * а не из выпуска: именно маршрут изделия-датчика задаёт, в каких
     * операциях комплектования он участвует. Пустой список означает, что
     * у серийника нет изделия или изделие не имеет базового маршрута —
     * диспетчер увидит пустой выбор и поймёт, что маршрут не настроен.
     */
    @Transactional(readOnly = true)
    public List<Operation> findSerialKittingOperations(Long serialId) {
        if (serialId == null) {
            return List.of();
        }

        ProductSerial serial = productSerialRepository
                .findById(serialId)
                .orElse(null);

        if (serial == null || serial.getProductClassifier() == null) {
            return List.of();
        }

        List<Operation> operations = new ArrayList<>();

        for (TechProcessOperation node
                : techProcessOperationRepository
                .findBasicProcessKittingByClassifier(
                        serial.getProductClassifier().getId(),
                        OperationTypeCode.KITTING.name())) {

            if (node.getOperation() != null) {
                operations.add(node.getOperation());
            }
        }

        return operations;
    }

    /**
     * Создаёт заказы-комплектующие по отмеченным парам
     * «серийник + операция»: каждой паре — отдельный ЗНП.
     *
     * Номер задаётся вручную (он приходит из внешнего учёта), поэтому
     * автоподбор для комплектования не выполняется, а пустой номер
     * отклоняется. Каждый ЗНП привязывается к ровно одному серийнику
     * родителя через SerialProductionOrder.
     *
     * Операция обязана быть комплектовочной и входить в базовый маршрут
     * изделия этого серийника — иначе ЗНП повесил бы операцию на чужое
     * изделие.
     */
    @Transactional
    public List<ProductionOrder> createKittingOrders(
            Long releaseId,
            List<Map<String, Object>> pairs,
            AppUser author
    ) {
        if (releaseId == null || pairs == null || pairs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не выбрано ни одной пары «серийник + операция»"
            );
        }

        ProductionOrder release = productionOrderRepository
                .findById(releaseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ верхнего уровня не найден"
                ));

        if (!release.isRoot() && !release.isSerialBasedKind()) {
            throw new IllegalStateException(
                    "Заказы-комплектующие создают только внутри выпуска"
            );
        }

        CustomerOrder customerOrder = requireCustomerOrder(release);
        LocalDate dueDate = resolveDueDate(customerOrder);

        List<ProductionOrder> created = new ArrayList<>();

        for (Map<String, Object> pair : pairs) {
            Long serialId = toLongOrNull(pair.get("serialId"));
            Long operationId = toLongOrNull(pair.get("operationId"));
            String number = trimToNullString(pair.get("number"));

            ProductSerial serial =
                    requireSerialOfRelease(serialId, release);
            Operation operation =
                    requireKittingOperation(operationId);

            requireSerialOperationAllowed(serial, operation);

            if (number == null) {
                throw new IllegalArgumentException(
                        "Укажите номер заказа для серийника «"
                                + serial.getFullNumber() + "»"
                );
            }

            if (productionOrderRepository.existsByNumber(number)) {
                throw new IllegalArgumentException(
                        "Заказ с номером «" + number
                                + "» уже существует"
                );
            }

            ProductionOrder order = new ProductionOrder();

            order.setNumber(number);
            order.setCustomerOrder(customerOrder);
            order.setParentProductionOrder(release);
            order.setZnpKind(ZnpKind.KITTING);
            order.setProductClassifier(serial.getProductClassifier());
            order.setProductionOperation(operation);
            order.setOrderDate(LocalDate.now());
            order.setDueDate(dueDate);
            order.setStatus(ProductionOrderStatus.NEW);
            order.setCreatedBy(author);
            order.setActive(true);

            ProductionOrder saved =
                    productionOrderRepository.save(order);

            serialKittingService.attach(
                    saved,
                    List.of(serial.getId()),
                    operation,
                    author
            );

            created.add(saved);
        }

        return created;
    }

    /** Серийник обязан быть выпущен именно этим заказом верхнего уровня. */
    private ProductSerial requireSerialOfRelease(
            Long serialId,
            ProductionOrder release
    ) {
        if (serialId == null) {
            throw new IllegalArgumentException(
                    "Серийный номер не выбран"
            );
        }

        ProductSerial serial = productSerialRepository
                .findById(serialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Серийный номер не найден"
                ));

        Long issuedOrderId = serial.getIssuedProductionOrder() == null
                ? null
                : serial.getIssuedProductionOrder().getId();

        if (!isSerialOfReleaseChain(issuedOrderId, release)) {
            throw new IllegalArgumentException(
                    "Датчик «" + serial.getFullNumber()
                            + "» выпущен другим заказом"
            );
        }

        return serial;
    }

    /**
     * Операция обязана входить в базовый маршрут изделия серийника —
     * тот же источник, что и список в findSerialKittingOperations.
     */
    private void requireSerialOperationAllowed(
            ProductSerial serial,
            Operation operation
    ) {
        if (serial.getProductClassifier() == null) {
            throw new IllegalArgumentException(
                    "У датчика «" + serial.getFullNumber()
                            + "» нет изделия, а значит и маршрута"
            );
        }

        boolean allowed = findSerialKittingOperations(serial.getId())
                .stream()
                .anyMatch(op -> op.getId().equals(operation.getId()));

        if (!allowed) {
            throw new IllegalArgumentException(
                    "Операция не входит в маршрут изделия датчика «"
                            + serial.getFullNumber() + "»"
            );
        }
    }

    /**
     * Создаёт ЗНП комплектования на каждый серийник
     * выпуска с ручным номером из формы создания выпуска.
     *
     * Пары «серийник ↔ номер ЗНП» выровнены по индексу:
     * releaseSerialIds[i] ↔ releaseZnpNumbers[i]. Операция на этом
     * этапе не привязывается (пустая), она выбирается позже.
     */
    private void createReleaseKittingOrders(
            ProductionOrder release,
            ProductionOrderForm form,
            AppUser author
    ) {
        List<Long> serialIds = form.getReleaseSerialIds();
        List<String> numbers = form.getReleaseZnpNumbers();

        if (serialIds == null || serialIds.isEmpty()) {
            return;
        }

        CustomerOrder customerOrder = requireCustomerOrder(release);
        LocalDate dueDate = resolveDueDate(customerOrder);

        /*
         * Изделия сгруппированы по введённому номеру ЗНП: один заказ
         * на комплектование законно покрывает несколько изделий,
         * поэтому строки с одинаковым номером должны сойтись в один
         * ProductionOrder с несколькими связями SerialProductionOrder,
         * а не завести несколько заказов-дублей (уникальность номера в
         * базе этого и не позволила бы).
         *
         * LinkedHashMap держит порядок第一次 встречающегося номера —
         * так перечень в карточке выпуска читается в том же порядке,
         * в котором диспетчер заполнял таблицу.
         */
        Map<String, List<ProductSerial>> byNumber = new LinkedHashMap<>();

        for (int index = 0; index < serialIds.size(); index++) {
            Long serialId = serialIds.get(index);
            String raw = numbers != null && index < numbers.size()
                    ? numbers.get(index)
                    : null;
            String number = trimToNullString(raw);

            ProductSerial serial =
                    requireSerialOfRelease(serialId, release);

            if (number == null) {
                throw new IllegalArgumentException(
                        "Заполните номер ЗНП для серийника "
                                + serial.getFullNumber() + ". "
                                + "Номер ЗНП обязателен для каждого серийника."
                );
            }

            /*
             * Номер ЗНП остаётся на самом серийнике: предпросмотр
             * выпуска и карточка изделия читают его, чтобы при
             * повторном открытии формы уже внесённые значения
             * оказались в своих полях.
             */
            serial.setZnpNumber(number);
            productSerialRepository.save(serial);

            byNumber
                    .computeIfAbsent(number, key -> new ArrayList<>())
                    .add(serial);
        }

        for (Map.Entry<String, List<ProductSerial>> entry
                : byNumber.entrySet()) {

            String number = entry.getKey();
            List<ProductSerial> serials = entry.getValue();

            /*
             * Заказ с таким номером мог быть заведён раньше — в этом
             * же выпуске он дополняется изделиями, а не создаётся
             * заново (тот же документ может добираться изделиями
             * постепенно).
             */
            ProductionOrder existing = productionOrderRepository
                    .findByNumber(number)
                    .filter(ProductionOrder::isActive)
                    .filter(candidate ->
                            release.getId().equals(candidate.getParentId()))
                    .orElse(null);

            if (existing != null) {
                List<Long> attached = new ArrayList<>();

                for (ProductSerial serial : serials) {
                    attached.add(serial.getId());
                }

                serialKittingService.attach(
                        existing, attached, null, author);

                continue;
            }

            if (productionOrderRepository.existsByNumber(number)) {
                throw new IllegalArgumentException(
                        "Заказ с номером «" + number
                                + "» уже существует и относится "
                                + "к другому выпуску."
                );
            }

            ProductionOrder order = new ProductionOrder();

            order.setNumber(number);
            order.setCustomerOrder(customerOrder);
            order.setParentProductionOrder(release);
            order.setZnpKind(ZnpKind.KITTING);
            order.setProductClassifier(
                    serials.get(0).getProductClassifier());
            order.setOrderDate(LocalDate.now());
            order.setDueDate(dueDate);
            order.setStatus(ProductionOrderStatus.NEW);
            order.setCreatedBy(author);
            order.setActive(true);

            ProductionOrder saved =
                    productionOrderRepository.save(order);

            List<Long> attached = new ArrayList<>();

            for (ProductSerial serial : serials) {
                attached.add(serial.getId());
            }

            serialKittingService.attach(
                    saved, attached, null, author);
        }
    }

    /**
     * Датчик допустим, если выпущен самим заказом
     * или любым из его родителей по цепочке (выпуск
     * для вложенного ЗНП).
     */
    private static boolean isSerialOfReleaseChain(
            Long issuedOrderId,
            ProductionOrder order
    ) {
        if (issuedOrderId == null) {
            return false;
        }

        ProductionOrder current = order;

        while (current != null) {
            if (issuedOrderId.equals(current.getId())) {
                return true;
            }

            current = current.getParentProductionOrder();
        }

        return false;
    }

    private static Long toLongOrNull(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        String text = value.toString().trim();

        if (text.isEmpty()) {
            return null;
        }

        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String trimToNullString(Object value) {
        if (value == null) {
            return null;
        }

        String text = value.toString().trim();

        return text.isEmpty() ? null : text;
    }

    /**
     * Изделия указанного типа — выбор для группового или ремонтного
     * заказа-комплектующего (миграция V37).
     *
     * Пустой тип (вид «комплектование») даёт пустой список: там состав
     * образуется из серийников выпуска, и справочник изделий форме не
     * нужен.
     */
    public List<ProductClassifier> findClassifierOptions(ProductType type) {
        if (type == null) {
            return List.of();
        }

        return productClassifierRepository.findByProductTypeOptions(type);
    }

    /**
     * Маршруты, применимые к изделию, — выбор техпроцесса в форме
     * группового заказа (миграция V40).
     *
     * Список строит репозиторий: базовый маршрут изделия идёт первым,
     * остальные по коду, и признак базовости отдаётся вместе с кодом,
     * чтобы шаблон подписывал его, а не вычислял заново.
     *
     * Пустое изделие даёт пустой список: без изделия выбирать маршрут
     * не из чего, и показывать в форме все маршруты подряд значило бы
     * предлагать диспетчеру чужие изделия.
     */
    public List<ClassifierRouteOptionView> findTechProcessOptions(
            Long classifierId) {

        return classifierId == null
                ? List.of()
                : techProcessRepository.findRoutesForClassifier(classifierId);
    }

    /**
     * Статусы заказа для выпадающего списка формы.
     */
    public List<ProductionOrderStatus> findStatuses() {
        return Arrays.asList(ProductionOrderStatus.values());
    }

    /**
     * Действующие заказы верхнего уровня (ЗП) — выбор родителя для
     * заказа-комплектующего в форме.
     */
    public List<ProductionOrder> findRootOrders() {
        return productionOrderRepository.findActiveRoots();
    }

    /**
     * Заказы покупателя, для которых выпуск ещё не заведён.
     *
     * Связь «один заказ покупателя — один заказ на производство»
     * закреплена уникальным ключом, поэтому занятые заказы
     * из выбора исключаются.
     *
     * Используются сущности, а не список-проекция: проекту
     * не хватает ни полей для привязки, ни порядка обхода позиций.
     */
    public List<CustomerOrder> findFreeCustomerOrders() {
        List<CustomerOrder> free = new ArrayList<>();

        for (CustomerOrder order : customerOrderRepository.findAll()) {
            if (!order.isActive()) {
                continue;
            }

            /*
             * Занятым считается только заказ покупателя, по которому
             * есть выпуск верхнего уровня (ЗП). Несколько ЗНП того же
             * заказчика законны, поэтому они не должны исключать заказ
             * из выбора — иначе после первого комплектующего создать
             * выпуск стало бы нельзя.
             */
            if (productionOrderRepository
                    .existsRootByCustomerOrderId(order.getId())) {
                continue;
            }

            free.add(order);
        }

        free.sort(Comparator.comparing(CustomerOrder::getNumber));

        return free;
    }

    /**
     * Создаёт заказ на производство и сразу выдаёт серийные номера
     * на каждый выпускаемый экземпляр.
     *
     * Порядок важен: заказ сохраняется первым, чтобы выданные следом
     * серийники успели сослаться на него (issued_production_order_id),
     * а состав выпуска формировался уже по сохранённому заголовку.
     */
    @Transactional
    public ProductionOrder create(ProductionOrderForm form, AppUser author) {

        /*
         * Уровень дерева (миграции V21, V37): выпуск (ЗП) или заказ-
         * комплектующий (ЗНП). Определяется не родителем, а выбором в
         * форме: групповой и ремонтный ЗНП существуют без родительского
         * выпуска, и «родителя нет» перестало означать «это выпуск».
         */
        ZnpKind kind = ZnpKind.ofNullable(form.getZnpKind());

        boolean component = form.isComponentLevel();

        /*
         * Заказ верхнего уровня вида не выбирает — ему оставляют
         * комплектование как значение по умолчанию, смысл из него не
         * извлекается.
         */
        if (!component) {
            kind = ZnpKind.KITTING;
        }

        /*
         * Родитель нужен только комплекту, вписываемому в выпуск:
         * он выбирает серийники этого выпуска и наследует заказчика.
         * Групповой и ремонтный заказы самостоятельны, и родитель для
         * них не предусмотрен — принятое из формы значение игнорируется.
         */
        ProductionOrder parent =
                component && kind.isSerialBased()
                        ? requireParent(
                                form.getParentProductionOrderId()
                        )
                        : null;

        /*
         * Состав «изделием и количеством» обязателен для всех видов,
         * кроме комплектования: групповой заказ без наименования и
         * количества не описывает ничего, и по нему нельзя ни набрать
         * ячейки, ни поставить работу в график.
         */
        ProductClassifier groupClassifier = null;
        BigDecimal groupQuantity = null;

        if (component && !kind.isSerialBased()) {
            groupClassifier = requireClassifier(
                    form.getProductClassifierId(),
                    kind
            );

            groupQuantity = requireQuantity(form.getQuantity(), kind);
        }

        /*
         * Заказ покупателя для комплектующего внутри выпуска берётся от
         * родителя, а не из формы: комплектующий делается для того же
         * заказчика, что и выпуск, и выбирать его заново нельзя, потому
         * что заказчик уже занят этим ЗП и в список свободных не
         * попадает. Собственное значение формы игнорируется сознательно
         * — иначе ЗНП мог бы «уйти» к чужому заказчику, минуя родителя.
         *
         * Самостоятельному заказу наследовать не от чего, поэтому его
         * заказчик выбирается в форме.
         */
        /*
         * Заказ покупателя для комплектовщика внутри выпуска берётся от
         * родителя, а не из формы: комплектующий делают для того же
         * заказчика, что и выпуск, и выбирать его заново нельзя, потому
         * что заказчик уже занят этим ЗП и в список свободных не
         * попадает. Значение формы игнорируется сознательно — иначе ЗНП
         * переприсвоили бы себе чужой заказ.
         *
         * Групповому заказу (V38) заказчика не назначают вовсе: он
         * самостоятелен, наследовать не от чего, а ограничение «один
         * заказ покупателя — один выпуск» отклоняло бы второй групповой
         * заказ того же заказчика. Заказ у группового заказа — свой
         * внутренний документ, а не заявка покупателя.
         */
        CustomerOrder customerOrder = parent != null
                ? requireCustomerOrder(parent)
                : kind.isReleaseBound()
                        ? findCustomerOrder(form.getCustomerOrderId())
                        : null;

        /*
         * Состав ЗП строится из позиций заказа покупателя, поэтому без
         * них выпуск сформировать не из чего. ЗНП состава не имеет: его
         * «состав» — датчики, в которые он входит (SerialProductionOrder),
         * поэтому к позициям покупателя он не привязан.
         */
        if (!component && customerOrder.getItems().isEmpty()) {
            throw new IllegalStateException(
                    "В заказе покупателя «" + customerOrder.getNumber()
                            + "» нет изделий — выпуск сформировать не из чего"
            );
        }

        /*
         * Ограничение «один заказ покупателя — один выпуск» с V21
         * действует только на заказы верхнего уровня: внутри выпуска
         * допустимо несколько ЗНП того же покупателя.
         */
        if (!component
                && productionOrderRepository
                .existsRootByCustomerOrderId(customerOrder.getId())) {
            throw new IllegalStateException(
                    "Для заказа покупателя «" + customerOrder.getNumber()
                            + "» заказ на производство уже создан"
            );
        }

        /*
         * Комплектовочная операция нужна заказу, привязанному к выпуску:
         * он выполняется операцией этого выпуска. Групповому заказу
         * операцию назначать нечем — в выпуск он не входит, поэтому
         * поле формы для него пустое, а работы задаёт техпроцесс
         * (см. requireTechProcess ниже).
         */
        Operation operation = component && kind.isSerialBased()
                ? requireKittingOperation(form.getProductionOperationId())
                : null;

        /*
         * Маршрут группового заказа (V40): работы ведут по техпроцессу
         * заказанного изделия, поэтому выбор обязателен и проверяется
         * вместе с изделием и количеством.
         */
        TechProcess techProcess = kind.requiresTechProcess()
                ? requireTechProcess(
                        form.getTechProcessId(),
                        groupClassifier,
                        kind
                )
                : null;

        LocalDate dueDate = resolveDueDate(customerOrder);

        validateDates(form.getOrderDate(), dueDate);

        String number = trimToEmpty(form.getNumber());

        /*
         * Номер ЗНП всегда вводит оператор: он приходит из внешнего
         * учёта и автоподбор по шаблону ЗНП-ГГГГ-NNN дал бы номер,
         * которого в документах нет. Для ЗП номер остаётся
         * автогенерируемым.
         */
        if (component && number.isBlank()) {
            throw new IllegalArgumentException(
                    "Укажите номер заказа на производство (комплектующего)"
            );
        }

        /*
         * Выбор датчиков обязателен для комплектующего на комплектование:
         * без него заказ не привязан ни к одному изделию и в матрице
         * выпуска не участвует. Проверяется до записи номера, чтобы при
         * отказе не оставалось заказа без состава.
         *
         * Групповым и ремонтным видам номера не нужны — их состав задан
         * изделием и количеством (см. ZnpKind.isSerialBased), поэтому
         * требование к ним неприменимо и отклонило бы корректную заявку.
         */
        if (component
                && kind.isSerialBased()
                && isEmpty(form.getSerialIds())) {
            throw new IllegalArgumentException(
                    "Выберите номера датчиков, в которые входит заказ"
            );
        }

        if (number.isBlank()) {
            number = nextNumber();
        }

        if (productionOrderRepository.existsByNumber(number)) {
            throw new IllegalArgumentException(
                    "Заказ с номером «" + number + "» уже существует"
            );
        }

        ProductionOrder order = new ProductionOrder();

        order.setNumber(number);
        order.setCustomerOrder(customerOrder);
        order.setParentProductionOrder(parent);
        order.setZnpKind(kind);
        order.setProductClassifier(groupClassifier);
        order.setQuantity(groupQuantity);
        order.setProductionOperation(operation);
        order.setTechProcess(techProcess);
        order.setOrderDate(form.getOrderDate());
        order.setDueDate(dueDate);

        /*
         * Новый выпуск всегда «Новый»: статусом управляют кнопки
         * карточки заказа («В работу», «Выполнен», «Отменить»),
         * в форме создания его нет.
         */
        order.setStatus(ProductionOrderStatus.NEW);

        order.setNote(blankToNull(form.getNote()));
        order.setCreatedBy(author);
        order.setActive(true);

        /*
         * Заголовок сохраняется до состава: серийники ссылаются
         * на заказ, и у них должен быть готовый идентификатор.
         */
        ProductionOrder saved = productionOrderRepository.save(order);

        /*
         * Серийники выдаёт только заказ верхнего уровня. Заказу-
         * комплектующему номер не нужен: он ничего не выпускает, а
         * входит внутрь уже выпущенного датчика — состав ЗНП задаётся
         * выбором номеров из родительского выпуска
         * (SerialProductionOrder).
         */
        if (!component) {
            buildRelease(
                    saved,
                    customerOrder,
                    form.getReleaseSerialIds()
            );

            ProductionOrder persisted =
                    productionOrderRepository.save(saved);

            /*
             * На этапе выпуска привязка к операциям НЕ делается:
             * на каждый серийник создаётся ЗНП комплектования
             * с ручным номером, привязанный к датчику без операции.
             */
            createReleaseKittingOrders(persisted, form, author);

            return productionOrderRepository.save(persisted);
        }

        /*
         * Датчики выбираются из выпуска родителя: комплектующий на
         * комплектование без них не имеет смысла — непонятно, в какое
         * изделие он входит. Групповому и ремонтному заказу номера не
         * назначаются: они заявлены изделием и количеством, а конкретные
         * номера появятся по ходу выпуска.
         */
        if (kind.isSerialBased()) {
            serialKittingService.attach(
                    saved,
                    form.getSerialIds(),
                    operation,
                    author
            );
        }

        return saved;
    }

    /**
     * Правка заказа на производство.
     *
     * Состав выпуска не пересобирается: серийные номера уже выданы
     * и закрепились за выпуском, поэтому меняется только шапка —
     * привязки, сроки, статус и примечание.
     */
    @Transactional
    public ProductionOrder update(Long id,
                                  ProductionOrderForm form,
                                  AppUser author) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ на производство не найден"
                        )
                );

        if (!order.isActive()) {
            throw new IllegalStateException(
                    "Удалённый заказ «" + order.getNumber()
                            + "» нельзя изменить"
            );
        }

        /*
         * Уровень дерева (ЗП/ЗНП) задаётся при создании и при правке
         * не меняется: смена родителя перекроила бы подчинённую
         * структуру выпуска и связи «изделие ⇄ заказ».
         */
        assertLevelUnchanged(
                order,
                id,
                form.getParentProductionOrderId()
        );

        /*
         * Выпуск или комплектующий — по сущности, а не по признаку
         * «родитель пуст»: групповой и ремонтный ЗНП (V37) существуют
         * самостоятельно, и выпуск из них не получается.
         */
        boolean root = order.isRoot();

        /*
         * Вид ЗНП при правке неизменный — по той же причине, что и
         * уровень дерева: смена вида меняет природу состава (перечень
         * серийников ↔ изделие с количеством), и прежний состав при
         * этом либо теряется, либо повисает незачем. Заказ пересоздают
         * заново.
         *
         * Разбор стоит выше заказчика: вид решает, положен ли заказу
         * заказ покупателя вовсе (см. ZnpKind#isReleaseBound).
         */
        ZnpKind kind = root
                ? ZnpKind.KITTING
                : ZnpKind.ofNullable(form.getZnpKind());

        if (!root) {
            assertKindUnchanged(order, kind);
        }

        /*
         * Заказчик наследуется от родительского выпуска только у
         * комплектующего внутри выпуска (см. create): в списке
         * свободных заказов он не числится, поэтому значение формы для
         * такого заказа игнорируется — иначе правка падала бы с
         * «выберите заказ покупателя».
         *
         * Выпуску выбирать заказчика есть из чего, он читает его из
         * формы. Групповому заказу (V38) заказчика не назначают: он
         * самостоятелен, и в форме поля заказчика нет.
         */
        boolean inheritsCustomer =
                order.getParentProductionOrder() != null;

        CustomerOrder customerOrder;

        if (inheritsCustomer) {
            customerOrder =
                    requireCustomerOrder(order.getParentProductionOrder());
        } else if (kind.isReleaseBound()) {
            customerOrder = findCustomerOrder(form.getCustomerOrderId());
        } else {
            customerOrder = null;
        }

        /*
         * Заказ может остаться привязанным к себе же: тогда смена
         * заказчика не считается повторным выпуском.
         */
        Long boundId = order.getCustomerOrder() == null
                ? null
                : order.getCustomerOrder().getId();

        /*
         * Ограничение «один заказ покупателя — один выпуск» проверяется
         * только для заказов верхнего уровня: несколько ЗНП того же
         * покупателя законны.
         */
        if (root
                && !customerOrder.getId().equals(boundId)
                && productionOrderRepository
                .existsRootByCustomerOrderId(customerOrder.getId())) {
            throw new IllegalStateException(
                    "Для заказа покупателя «" + customerOrder.getNumber()
                            + "» заказ на производство уже создан"
            );
        }

        /*
         * Комплектовочная операция — только заказу внутри выпуска
         * (см. create): групповой заказ в выпуск не входит, и поле
         * формы для него пустое.
         */
        Operation operation = !root && kind.isSerialBased()
                ? requireKittingOperation(form.getProductionOperationId())
                : null;

        LocalDate dueDate = resolveDueDate(customerOrder);

        validateDates(form.getOrderDate(), dueDate);

        String number = trimToEmpty(form.getNumber());

        if (number.isBlank()) {
            number = order.getNumber();
        }

        if (productionOrderRepository
                .existsByNumberAndIdNot(number, id)) {
            throw new IllegalArgumentException(
                    "Заказ с номером «" + number + "» уже существует"
            );
        }

        ProductClassifier groupClassifier = null;
        BigDecimal groupQuantity = null;

        if (!root && !kind.isSerialBased()) {
            groupClassifier = requireClassifier(
                    form.getProductClassifierId(),
                    kind
            );

            groupQuantity = requireQuantity(form.getQuantity(), kind);
        }

        /*
         * Маршрут группового заказа при правке проверяется так же, как
         * при создании: он обязателен этим видам и должен относиться к
         * заказанному изделию.
         */
        TechProcess techProcess = kind.requiresTechProcess()
                ? requireTechProcess(
                        form.getTechProcessId(),
                        groupClassifier,
                        kind
                )
                : null;

        /*
         * Состав комплектующего при правке обязателен так же, как при
         * создании: заказ без датчиков не участвует в матрице выпуска.
         * Групповым и ремонтным видам вместо датчиков нужны изделие и
         * количество — они проверены выше.
         */
        if (!root
                && kind.isSerialBased()
                && isEmpty(form.getSerialIds())) {
            throw new IllegalArgumentException(
                    "Выберите номера датчиков, в которые входит заказ"
            );
        }

        order.setNumber(number);
        order.setCustomerOrder(customerOrder);
        order.setZnpKind(kind);
        order.setProductClassifier(groupClassifier);
        order.setQuantity(groupQuantity);
        order.setProductionOperation(operation);
        order.setTechProcess(techProcess);
        order.setOrderDate(form.getOrderDate());
        order.setDueDate(dueDate);
        order.setNote(blankToNull(form.getNote()));

        /*
         * Статус при правке не меняется: в форме его нет, перевод
         * между статусами выполняется только целевыми действиями
         * (plan / complete / cancel).
         */
        ProductionOrder saved = productionOrderRepository.save(order);

        /*
         * Выбранные датчики синхронизируются, а не дописываются:
         * снятые в форме номера должны отвязаться. Отвязка с уже
         * записанными операциями отклоняется внутри сервиса, поэтому
         * история выполнения не теряется.
         *
         * Синхронизация нужна только заказам на комплектование: у
         * групповых и ремонтных видов связей с серийниками нет, и
         * вызов с пустым списком снимал бы их при каждой правке.
         */
        if (!root && kind.isSerialBased()) {
            serialKittingService.sync(
                    saved,
                    form.getSerialIds(),
                    operation,
                    author
            );
        }

        return saved;
    }

    /**
     * Взятие заказа на производство в работу.
     *
     * Одновременно в работу переводятся его позиции и серийники:
     * с этого момента выпуск считается начатым.
     */
    @Transactional
    public ProductionOrder plan(Long id) {

        ProductionOrder order = requireActiveOrder(id);

        ProductionOrderStatus status = order.getStatus();

        if (status == ProductionOrderStatus.DONE
                || status == ProductionOrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» уже закрыт и не может быть взят в работу"
            );
        }

        /*
         * Выпуск берут в работу только когда номер ЗНП внесён напротив
         * каждого серийника состава: статус «В работе» открывает заказ
         * цеху, и изделие без документа комплектования ушло бы в работу
         * неучтённым. Проверка держится здесь, а не в форме, потому что
         * ЗНП дописывают и после создания заказа.
         */
        long total = productionOrderItemRepository.countByOrderId(id);
        long missing = productionOrderItemRepository
                .countWithoutZnpByOrderId(id);

        if (missing > 0) {
            throw new IllegalStateException(
                    "В заказе «" + order.getNumber()
                            + "» внесены не все ЗНП: осталось заполнить "
                            + missing + " из " + total
                            + " — заполните номер ЗНП напротив каждого серийника"
            );
        }

        /*
         * Заказ-комплектующий берут в работу только когда каждая
         * комплектовочная операция изделия, в которое он вошёл, закрыта
         * вложенным ЗНП. Без этого цех получил бы изделие, у которого
         * часть операций комплектования не оформлена ни одним
         * документом, — ровно то, на что указал диспетчер.
         *
         * Проверка держится здесь, а не в карточке: вложенные ЗНП
         * дописывают и после создания заказа, и тот же страж обязан
         * сработать и при повторном взятии в работу.
         */
        if (order.isSerialBasedKind()) {
            requireNestedZnpCovered(order);
        }

        /*
         * Заказ берут в работу только когда его вложенные заказы-
         * комплектующие, для которых уже заявлены операции, поставлены в
         * планирование (у каждой такой операции есть плановая дата).
         * Иначе «В работе» родителя каскадом перевёл бы непропланированный
         * ЗНП в работу — ровно тот случай, на который указал диспетчер:
         * заказ в работе, а работы по нему ни в каком плане нет.
         *
         * Проверка держится здесь, а не в каскаде applyStatus: отказ
         * обязан вернуться до любых изменений статуса, иначе часть дерева
         * осталась бы переведённой.
         */
        requireChildrenPlanned(order);

        applyStatus(order, ProductionOrderStatus.IN_WORK);

        /*
         * Работы группового и ремонтного заказа (V38) ведутся по его
         * собственному техпроцессу (V40), а цех читает план, а не
         * матрицу изделия: без строк графика «Монтаж подсборок» не
         * появился бы ни в распределении, ни в «Мои операции», и
         * следующая после комплектования операция осталась бы невидимой
         * даже после того, как склад внёс все ячейки.
         *
         * План заводится один раз и только если его ещё нет: повторное
         * взятие в работу не должно пересобирать график, потому что
         * пересборка стёрла бы уже проведённые строки — единственный
         * факт закрытия операции экземпляра. Перепланирование остаётся
         * отдельным действием, как у заказа покупателя.
         */
        if (scheduleOwnRoute(order) && !isPlanned(order)) {
            orderSchedulingService.confirmProductionOrder(order.getId());
        }

        return order;
    }

    /**
     * Все ли вложенные заказы-комплектующие с операциями спланированы.
     *
     * «Поставлен в планирование» значит, что по основанию заказа (заказу
     * покупателя) расчёт внесён в план: строка его комплектовочной операции
     * есть в графике в статусе CONFIRMED, а плановая дата у такой строки
     * обязательна на уровне схемы. Черновик (DRAFT) планом не считается: он
     * ничего не занимает и цеху не виден.
     *
     * Проверка идёт по всей цепочке вниз, а не на один уровень: статус
     * «В работе» распространяется каскадом на всё поддерево, поэтому
     * непропланированный ЗНП на любом уровне ушёл бы в работу вместе с
     * родителем — ровно то, на что указал диспетчер. Закрытые
     * (выполненные/отменённые) заказы не обходятся: каскад их не трогает,
     * и требовать плана от закрытого заказа незачем.
     *
     * Заказ без комплектовочной операции в счёт не идёт: планировать его
     * нечем, и требование плана заблокировало бы заказ, для которого работа
     * ещё не заявлена.
     */
    private void requireChildrenPlanned(ProductionOrder order) {

        Set<String> unplannedNumbers = new LinkedHashSet<>();
        Set<Long> visited = new HashSet<>();
        List<ProductionOrder> pending = new ArrayList<>();

        if (order.getId() != null) {
            visited.add(order.getId());
            pending.add(order);
        }

        while (!pending.isEmpty()) {

            ProductionOrder node = pending.remove(pending.size() - 1);

            for (ProductionOrder child
                    : productionOrderRepository
                    .findActiveChildren(node.getId())) {

                if (child.getId() == null
                        || child.getStatus() == null
                        || !visited.add(child.getId())) {
                    continue;
                }

                if (isClosed(child.getStatus())) {
                    continue;
                }

                if (!isPlanned(child)) {
                    unplannedNumbers.add(child.getNumber());
                }

                pending.add(child);
            }
        }

        if (!unplannedNumbers.isEmpty()) {
            throw new IllegalStateException(
                    "Нельзя взять заказ «" + order.getNumber()
                            + "» в работу: входящие заказы-комплектующие ("
                            + String.join(", ", unplannedNumbers)
                            + ") не поставлены в планирование — сначала "
                            + "внесите их операции в план"
            );
        }
    }

    /**
     * Ведётся ли заказ по собственному техпроцессу, то есть заводится ли
     * ему план второй ветви (V56).
     *
     * Признак берётся у вида заказа, а не из непустоты techProcess:
     * маршрутом снабжаются групповые и ремонтные виды
     * (ZnpKind#requiresTechProcess), а комплектованию и выпуску работы
     * ставит выпуск, внутрь которого они входят.
     */
    private static boolean scheduleOwnRoute(ProductionOrder order) {

        return order.getZnpKind() != null
                && order.getZnpKind().requiresTechProcess()
                && order.getTechProcess() != null;
    }

    /**
     * Спланирован ли заказ-комплектующий: есть ли внесённая в план строка
     * его операции по его же заказу покупателя.
     *
     * Идентификаторы читаются с ленивых связей напрямую — в транзакции
     * это не вызывает догрузки, а разыменовывать справочники ради сверки
     * ключа незачем.
     */
    private boolean isPlanned(ProductionOrder order) {

        /*
         * Групповой и ремонтный заказ (V38) ведёт техпроцесс, а не
         * комплектовочная операция выпуска, и заказа покупателя у него
         * нет. План такого заказа — строки второй ветви графика (V56),
         * заведённые по его же заказу; без этой ветви заказ считался бы
         * не спланированным навсегда и блокировал бы взятие в работу
         * родителя.
         */
        if (order.getZnpKind() != null
                && order.getZnpKind().requiresTechProcess()) {

            return order.getId() != null
                    && orderScheduleRowRepository.countByProductionOrder_IdAndStatus(
                            order.getId(),
                            ScheduleRowStatus.CONFIRMED
                    ) > 0;
        }

        Long operationId = order.getProductionOperation() == null
                ? null
                : order.getProductionOperation().getId();

        if (operationId == null) {
            return true;
        }

        Long customerOrderId = order.getCustomerOrder() == null
                ? null
                : order.getCustomerOrder().getId();

        if (customerOrderId == null) {
            return false;
        }

        return orderScheduleRowRepository.countConfirmedByOrderAndOperation(
                customerOrderId,
                operationId,
                ScheduleRowStatus.CONFIRMED
        ) > 0;
    }

    /**
     * Все ли операции комплектования изделия закрыты вложенным ЗНП.
     *
     * Состав заказ-комплектующего — операции базового маршрута того
     * изделия, в которое он вошёл. Операция считается закрытой, если в
     * это изделие вошёл активный заказ именно этой операцией; сам
     * проверяемый заказ закрывает свою операцию и требовать второго
     * ЗНП на неё незачем.
     *
     * Изделие без настроенного маршрута проверять нечем — пустой
     * перечень операций не блокирует взятие в работу.
     */
    private void requireNestedZnpCovered(ProductionOrder order) {

        Set<String> uncoveredNames = new LinkedHashSet<>();

        for (ProductSerial serial
                : serialKittingService.findAttachedSerials(order.getId())) {

            Set<Long> covered = serialProductionOrderRepository
                    .findCoveredOperationIdsBySerialId(serial.getId());

            for (Operation operation
                    : findSerialKittingOperations(serial.getId())) {

                if (operation.getId() != null
                        && !covered.contains(operation.getId())) {

                    uncoveredNames.add(
                            operation.getOperationName() == null
                                    ? ""
                                    : operation.getOperationName().getName()
                    );
                }
            }
        }

        if (!uncoveredNames.isEmpty()) {
            throw new IllegalStateException(
                    "В заказ-комплектующий «" + order.getNumber()
                            + "» не внесены номера ЗНП для операций: "
                            + String.join(", ", uncoveredNames)
                            + " — создайте вложенные заказы-комплектующие"
                            + " напротив каждой операции"
            );
        }
    }

    /**
     * Перевод всех позиций заказа в «Выполнена».
     *
     * Заказ закрывается только когда выпущены все экземпляры:
     * частичная готовность фиксируется статусом PARTIALLY_DONE,
     * который диспетчер ставит при частичной сдаче.
     */
    @Transactional
    public ProductionOrder complete(Long id) {

        ProductionOrder order = requireActiveOrder(id);

        if (order.getStatus() == ProductionOrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Отменённый заказ «" + order.getNumber()
                            + "» нельзя выполнить"
            );
        }

        /*
         * Состав есть только у выпуска верхнего уровня: у заказа-
         * комплектующего «состав» — датчики, в которые он входит
         * (SerialProductionOrder), поэтому позиций у него нет и проверка
         * «закрывать нечего» к нему неприменима — иначе ЗНП нельзя было
         * бы закрыть вообще.
         */
        if (order.isRoot()
                && productionOrderItemRepository.countByOrderId(id) == 0) {
            throw new IllegalStateException(
                    "В заказе «" + order.getNumber()
                            + "» нет изделий — закрывать нечего"
            );
        }

        applyStatus(order, ProductionOrderStatus.DONE);

        return order;
    }

    /**
     * Применяет статус к заказу и ко всему, что в него входит.
     *
     * Заказ-комплектующий не имеет смысла отдельно от выпуска: он либо
     * собран вместе с ним, либо не собран. Поэтому статус распространяется
     * вниз по всей цепочке состава, а вверх — никогда: выпуск может
     * достраиваться остальными комплектующими.
     *
     * Статусы позиций и серийников выводятся из статуса заказа через
     * switch без default: компилятор требует перечислить все значения
     * ProductionOrderStatus, поэтому новый статус в enum невозможно
     * добавить, молча не описав для него перевод.
     *
     * Позиции и серийники обходятся у каждого заказа отдельно: у ЗНП их
     * нет, и запрос по его идентификатору просто ничего не изменит.
     */
    private void applyStatus(ProductionOrder order,
                             ProductionOrderStatus status) {

        /*
         * Обход идёт по всей цепочке, а не на один уровень: выпуск
         * включает ЗНП, а в ЗНП, в свою очередь, входят свои ЗНП, и
         * выпуск считается начатым для всего состава сразу. Порядок —
         * от родителя к детям; множество посещённых нужно как защита от
         * цикла, который сделала бы бы рекурсия бесконечной.
         */
        List<ProductionOrder> tree = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        List<ProductionOrder> pending = new ArrayList<>();
        pending.add(order);
        visited.add(order.getId());

        while (!pending.isEmpty()) {

            ProductionOrder node = pending.remove(pending.size() - 1);
            tree.add(node);

            for (ProductionOrder child
                    : productionOrderRepository
                    .findActiveChildren(node.getId())) {

                if (child.getId() == null || !visited.add(child.getId())) {
                    continue;
                }

                /*
                 * Взятие в работу не «воскрешает» комплектующие, по
                 * которым выпуск уже закрыт или отменён: их статус —
                 * запись о завершённой работе, и перезапись потеряла бы
                 * историю. Поддерево закрытого заказа не обходится
                 * целиком по той же причине. На закрытие и отмену
                 * родителя правило не распространяется: закрытый выпуск
                 * закрывает и всё, что в него входит (требование
                 * диспетчера).
                 */
                if (status == ProductionOrderStatus.IN_WORK
                        && isClosed(child.getStatus())) {
                    continue;
                }

                pending.add(child);
            }
        }

        for (ProductionOrder node : tree) {

            node.setStatus(status);
            productionOrderRepository.save(node);
        }

        /*
         * Позиции и серийники переводятся одним запросом на узел.
         * У отменённого выпуска позиции остаются как были: серийные
         * номера не переиздаются, а возврат позиции в «Новую»
         * означал бы потерю записи о том, на какой стадии выпуск
         * остановили.
         */

        ProductionItemStatus itemStatus = switch (status) {
            case NEW -> ProductionItemStatus.NEW;
            case IN_WORK, PARTIALLY_DONE -> ProductionItemStatus.IN_WORK;
            case DONE -> ProductionItemStatus.DONE;
            case CANCELLED -> null;
        };

        ProductSerialStatus serialStatus = switch (status) {
            case NEW -> ProductSerialStatus.NEW;
            case IN_WORK, PARTIALLY_DONE -> ProductSerialStatus.IN_WORK;
            case DONE -> ProductSerialStatus.DONE;
            case CANCELLED -> null;
        };

        for (ProductionOrder node : tree) {

            if (itemStatus != null) {
                productionOrderItemRepository.updateStatusByOrderId(
                        node.getId(),
                        itemStatus
                );
            }

            if (serialStatus != null) {
                productSerialRepository.updateStatusByOrderId(
                        node.getId(),
                        serialStatus
                );
            }
        }
    }

    /**
     * Отмена заказа на производство.
     *
     * Выпуск прекращается вместе с вложенными заказами-комплектующими.
     * Позиции и серийники остаются в том статусе, на котором выпуск
     * остановили: отменённый заказ больше не участвует в работе, но
     * его серийные номера остаются в реестре — номера не переиздаются,
     * а история остановки должна читаться.
     */
    @Transactional
    public ProductionOrder cancel(Long id) {

        ProductionOrder order = requireActiveOrder(id);

        if (order.getStatus() == ProductionOrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber() + "» уже отменён"
            );
        }

        if (order.getStatus() == ProductionOrderStatus.DONE) {
            throw new IllegalStateException(
                    "Выполненный заказ «" + order.getNumber()
                            + "» нельзя отменить"
            );
        }

        /*
         * Отмена распространяется на вложенные заказы-комплектующие:
         * отменённый выпуск не может содержать действующие комплектующие.
         * Позиции и серийники при этом не переводятся — см. applyStatus.
         */
        applyStatus(order, ProductionOrderStatus.CANCELLED);

        return order;
    }

    /**
     * Возврат отменённого заказа-комплектующего (ЗНП) в работу.
     *
     * Отменённый выпуск остаётся закрытым: его позиции и серийники
     * помнят, на какой стадии остановили выпуск. ЗНП возвращают —
     * заказ переходит в «Новый», и диспетчер берёт его в работу
     * заново, уже с внесёнными номерами ЗНП (проверка в plan).
     *
     * Возврат симметричен отмене: cancel перевёл в «Отменён» всё
     * поддерево, поэтому reopen возвращает в «Новый» тоже всё
     * поддерево — иначе вложенные ЗНП остались бы отменёнными
     * внутри возвращённого заказа.
     */
    @Transactional
    public ProductionOrder reopen(Long id) {

        ProductionOrder order = requireActiveOrder(id);

        if (!order.isComponent()) {
            throw new IllegalStateException(
                    "Возвращать в работу можно только заказ-комплектующий"
                            + " (ЗНП), а заказ «" + order.getNumber()
                            + "» — выпуск"
            );
        }

        if (order.getStatus() != ProductionOrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» не отменён — возвращать в работу нечего"
            );
        }

        applyStatus(order, ProductionOrderStatus.NEW);

        return order;
    }

    /**
     * Удаление заказа на производство.
     *
     * Удаляется заказ, по которому выпуск ещё не довёден до события
     * склада: «Новый» — план, «В работе» и «Частично выполнен» — тоже
     * снимаются, пока по заказу НЕ было проведено ни одной складской
     * операции (требование диспетчера). Закрытый («Выполнен») и
     * отменённый заказы — запись истории, их не удаляют.
     *
     * Признак «были складские операции» — проведение фактом
     * ({@code product_serial_operations.actual_date}) и отметка приёмки
     * ячейки на склад ({@code product_serials.accepted_at}): их ставит
     * только склад (приёмка ЗНП, приёмка после БВК), поэтому факт и
     * есть граница «снять с работы можно / уже нельзя». Пока таких
     * записей нет, заказ содержит один план, и его удаление ничего не
     * стирает из истории цеха и склада.
     *
     * С планом поступают как при удалении заказа покупателя: снятый
     * заказ не оставляет в цеху работу, которой не бывать, —
     * запланированные (без факта) операции own-заказа удаляются.
     * Проведённые фактом переживают заказ, но до них дело не доходит:
     * заказ с фактами отсекается проверкой выше.
     *
     * Серийные номера выпуска возвращаются в свободный резерв: снимается
     * ссылка на заказ, висячие связи комплектования и статус «В работе»
     * (номер снова «Выпущен»). Реестр при этом неизменяем — номера не
     * переиздаются и занятая серия остаётся занятой.
     */
    @Transactional
    public ProductionOrder delete(Long id) {

        ProductionOrder order = requireActiveOrder(id);

        if (isClosedStatus(order.getStatus())) {
            throw new IllegalStateException(
                    "Удалить можно незакрытый заказ, а заказ «"
                            + order.getNumber() + "» в статусе «"
                            + order.getStatus().getDisplayName() + "»"
            );
        }

        /*
         * Заказ в работе удаляют, только если склад по нему ещё не
         * работал. Проверка предшествует освобождению номеров: отказ
         * обязан вернуться до любых изменений, иначе часть состава
         * осталась бы отвязанной от заказа.
         */
        if (hasWarehouseOperations(order)) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» удалить нельзя: по нему уже проведены "
                            + "операции на складе"
            );
        }

        /*
         * Выпуск с вложенными заказами-комплектующими удалять нельзя:
         * ЗНП повис бы со ссылкой на неактивный родитель, а связь
         * «изделие ⇄ заказ» потеряла бы выпуск, в который он входит.
         */
        if (productionOrderRepository.existsActiveChildren(id)) {
            throw new IllegalStateException(
                    "В заказ «" + order.getNumber()
                            + "» входят заказы-комплектующие — "
                            + "сначала удалите их"
            );
        }

        /*
         * Уровень заказа фиксируется ДО снятий: isRoot() читает
         * ленивую связь parentProductionOrder, а запросы ниже идут с
         * clearAutomatically и очищают контекст — обращение к связи
         * после них был бы LazyInitializationException (open-in-view
         * выключен). Тот же приём, что при удалении заказа покупателя.
         */
        boolean root = order.isRoot();

        /*
         * Плановые операции own-заказа снимаются первыми: запрос идёт с
         * clearAutomatically и очищает контекст, поэтому серийники и
         * заказ перечитываются ниже заново.
         */
        productSerialOperationRepository
                .deletePlannedByProductionOrderId(id);

        /*
         * План второй ветви (V56) снимается тем же порядком: у удалённого
         * заказа не должно оставаться работы, которой не бывать. Проведённые
         * строки остаются — completedAt есть единственный факт закрытия
         * операции экземпляра, а до них дело не доходит: заказ с фактом
         * отвергается проверкой выше.
         */
        orderScheduleRowRepository.deleteUncompletedByProductionOrderId(id);

        /*
         * Освобождаем серийные номера, выданные выпуском: снимаем ссылку
         * на заказ, удаляем висячие ссылки комплектования
         * (SerialProductionOrder) и возвращаем статус «Выпущен», чтобы
         * номер вернулся в свободный резерв неотмеченным складом.
         */
        if (root) {
            for (ProductSerial serial : productSerialRepository
                    .findByProductionOrderId(order.getId())) {

                serialProductionOrderRepository
                        .findDetailsBySerialId(serial.getId())
                        .forEach(link ->
                                serialProductionOrderRepository
                                        .delete(link));

                serial.setStatus(ProductSerialStatus.NEW);
                serial.setIssuedProductionOrder(null);
                productSerialRepository.saveAndFlush(serial);
            }
        }

        ProductionOrder fresh = requireActiveOrder(id);
        fresh.setActive(false);

        return productionOrderRepository.save(fresh);
    }

    /**
     * Закрыт ли статус: заказ-запись истории, его не снимают с работы.
     */
    private static boolean isClosedStatus(ProductionOrderStatus status) {
        return status == ProductionOrderStatus.DONE
                || status == ProductionOrderStatus.CANCELLED;
    }

    /**
     * Проведены ли по заказу складские операции.
     *
     * У заказа-комплектующего (ЗНП) достаточно фактов в его рамках:
     * приёмка ячеек закрывает комплектовочную операцию именно этим
     * заказом, и это и есть его складское событие.
     *
     * У выпуска (ЗП) собственных складских операций нет, но его номера
     * укомплектованы чужими ЗНП, и приёмка такого ЗНП оставила факт или
     * отметку приёмки на изделии выпуска. Освободить номер, уже
     * побывавший на складе, — потерять прослеживаемость, поэтому выпуск
     * проверяют и по своим изделиям: любой факт операции либо отметка
     * приёмки по выпущенным номерам.
     */
    private boolean hasWarehouseOperations(ProductionOrder order) {

        if (productSerialOperationRepository
                .existsPerformedByProductionOrderId(order.getId())) {
            return true;
        }

        if (!order.isRoot()) {
            return false;
        }

        List<Long> serialIds = productSerialRepository
                .findByProductionOrderId(order.getId()).stream()
                .map(ProductSerial::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        if (serialIds.isEmpty()) {
            return false;
        }

        return productSerialOperationRepository
                        .existsPerformedBySerialIdIn(serialIds)
                || productSerialRepository
                        .existsAcceptedByIdIn(serialIds);
    }

    /**
     * Состав выпуска по заказу покупателя: одна строка — один экземпляр.
     *
     * Позиция и заказ покупателя остаются указанными в строке,
     * чтобы выпуск был прослеживаем вплоть до позиции договора.
     */
    private void buildRelease(ProductionOrder order,
                              CustomerOrder customerOrder,
                              List<Long> chosenSerialIds) {

        /*
         * Отмеченные диспетчером резервы разложены по позициям: строка
         * выпуска берёт номер своей позиции, а не первого попавшегося.
         */
        Map<Long, List<ProductSerial>> claimed =
                chooseReservedSerials(customerOrder, chosenSerialIds);

        int position = 1;

        for (CustomerOrderItem customerItem : customerOrder.getItems()) {

            ProductClassifier classifier = customerItem.getProductClassifier();

            if (classifier == null) {
                throw new IllegalStateException(
                        "В позиции №" + customerItem.getPosition()
                                + " заказа покупателя не указано изделие"
                );
            }

            int quantity = instanceCount(customerItem.getQuantity());

            List<ProductSerial> reserved =
                    claimed.getOrDefault(customerItem.getId(), List.of());

            for (int index = 0; index < quantity; index++) {

                ProductionOrderItem item = new ProductionOrderItem();

                item.setProductClassifier(classifier);
                item.setCustomerOrderItem(customerItem);
                /*
                 * Отмеченный резерв переиспользуется, а не перевыпускается:
                 * новый номер на тот же экземпляр оставил бы в базе второй
                 * действующий серийник на одну позицию договора.
                 */
                item.setSerial(index < reserved.size()
                        ? serialIssueService.claimForRelease(
                                reserved.get(index), order)
                        : issueSerial(classifier, order));

                /*
                 * Порядок задаётся вручную: ProductionOrder.addItem
                 * его не проставляет, а колонка NOT NULL и участвует
                 * в уникальном ключе (production_order_id, position).
                 */
                item.setPosition(position++);
                item.setStatus(ProductionItemStatus.NEW);

                order.addItem(item);
            }
        }
    }

    /**
     * Отмеченные диспетчером резервы, разложенные по позициям заказа.
     *
     * Ключ — id позиции, значение — её номера по возрастанию серии: строка
     * выпуска берёт номер своей позиции, а не первый свободный по заказу,
     * иначе у двух позиций одного рода экземпляры обменялись бы номерами.
     *
     * Отмеченный номер обязан быть резервом именно выбранного заказа.
     * Номер другого выпуска, серийник, уже укомплектованный ЗНП, или чужая
     * позиция — это порча прослеживаемости, а не вариант выбора, поэтому
     * каждый номер проверяется, а не молча отбрасывается.
     */
    private Map<Long, List<ProductSerial>> chooseReservedSerials(
            CustomerOrder customerOrder,
            List<Long> chosenSerialIds) {

        if (chosenSerialIds == null || chosenSerialIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, CustomerOrderItem> items = new LinkedHashMap<>();

        for (CustomerOrderItem item : customerOrder.getItems()) {
            items.put(item.getId(), item);
        }

        Map<Long, List<ProductSerial>> claimed = new LinkedHashMap<>();

        for (Long serialId : new LinkedHashSet<>(chosenSerialIds)) {

            ProductSerial serial = productSerialRepository
                    .findById(serialId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Серийный номер не найден"
                    ));

            if (serial.getIssuedProductionOrder() != null) {
                throw new IllegalStateException(
                        "Номер «" + serial.getFullNumber()
                                + "» уже выпущен заказом «"
                                + serial.getIssuedProductionOrder().getNumber()
                                + "»"
                );
            }

            if (serialProductionOrderRepository.countBySerial_Id(serialId) > 0) {
                throw new IllegalArgumentException(
                        "Номер «" + serial.getFullNumber()
                                + "» уже укомплектован заказом-комплектующим — "
                                + "в новом выпуске он не участвует"
                );
            }

            CustomerOrderItem item = serial.getCustomerOrderItem();

            if (item == null || !items.containsKey(item.getId())) {
                throw new IllegalArgumentException(
                        "Номер «" + serial.getFullNumber()
                                + "» не относится к позициям выбранного заказа"
                );
            }

            List<ProductSerial> reserved = claimed.computeIfAbsent(
                    item.getId(),
                    key -> new ArrayList<>());

            reserved.add(serial);

            if (reserved.size() > instanceCount(item.getQuantity())) {
                throw new IllegalArgumentException(
                        "В позиции №" + item.getPosition()
                                + " отмечено больше номеров, чем заявлено экземпляров"
                );
            }
        }

        claimed.values().forEach(numbers -> numbers.sort(
                Comparator.comparingInt(ProductSerial::getSerial)));

        return claimed;
    }

    /**
     * Выдаёт следующий серийный номер для изделия.
     *
     * Ряд номеров привязан к области (numbering_key): для прибора,
     * ячейки и системы это сам классификатор, для датчика — его род.
     * Поэтому максимум берётся по области, а не по типу изделия:
     * 888800122 → 888800123, а у датчиков одного рода ряд сквозной
     * (119200111 → 119400112).
     */
    private ProductSerial issueSerial(ProductClassifier classifier,
                                      ProductionOrder order) {

        ProductType productType = classifier.getProductType();

        if (productType == null) {
            throw new IllegalStateException(
                    "Изделию «" + classifier.getCode()
                            + "» не назначен тип продукции"
            );
        }

        String numberingKey = SerialNumbering.keyFor(classifier);

        Integer max = productSerialRepository
                .findMaxSerialByNumberingKey(numberingKey);

        int candidate = (max == null ? 0 : max) + 1;

        /*
         * Все номера текущего выпуска уже видны в persistence-контексте,
         * но ещё не записаны, поэтому занятые значения собираются из
         * уже выданных в этом заказе серийников.
         */
        Set<Integer> taken = takenSerials(numberingKey, order);

        while (candidate <= MAX_SERIAL
                && (taken.contains(candidate)
                || productSerialRepository
                .existsByNumberingKeyAndSerial(numberingKey, candidate))) {
            candidate++;
        }

        if (candidate > MAX_SERIAL) {
            throw new IllegalStateException(
                    "Серии изделия «" + classifier.getCode()
                            + "» исчерпаны: пятизначной нумерации не хватает"
            );
        }

        ProductSerial serial = new ProductSerial();

        serial.setProductType(productType);
        serial.setSerial(candidate);
        serial.setProductClassifier(classifier);

        /*
         * Род датчика записывается рядом с номером: для типа SENSOR он
         * и есть область нумерации, а сгенерированный столбец
         * numbering_key считается именно по этой колонке.
         */
        serial.setSensorCatalog(
                productType == ProductType.SENSOR
                        ? classifier.getSensorCatalog()
                        : null
        );

        serial.setIssuedProductionOrder(order);
        serial.setStatus(ProductSerialStatus.NEW);

        return productSerialRepository.save(serial);
    }

    /**
     * Серии, занятые этому заказу в пределах той же области нумерации.
     */
    private Set<Integer> takenSerials(String numberingKey,
                                      ProductionOrder order) {

        Set<Integer> taken = new HashSet<>();

        for (ProductionOrderItem item : order.getItems()) {
            ProductSerial serial = item.getSerial();

            if (serial == null || serial.getProductClassifier() == null) {
                continue;
            }

            if (numberingKey.equals(
                    SerialNumbering.keyFor(serial.getProductClassifier()))) {
                taken.add(serial.getSerial());
            }
        }

        return taken;
    }

    /**
     * Сколько экземпляров выпускается по позиции заказа покупателя.
     *
     * Количество в заказе покупателя дробное (единицы измерения
     * различаются), а экземпляр всегда целый: дробное значение
     * округляется вверх, минимум один экземпляр.
     *
     * Статический метод: тот же расчёт нужен планировщику, а звать из
     * него бин сервиса заказов значило бы замкнуть круг зависимостей
     * (сервис заказов, в свою очередь, просит у планировщика заводку
     * строк графика для группового ЗНП).
     */
    public static int instanceCount(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return 1;
        }

        return quantity.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /**
     * Родитель для комплектующего внутри выпуска.
     *
     * Отдельный метод потому, что обязательность родителя зависит от
     * вида: комплектование обязано ссылаться на выпуск, а групповой и
     * ремонтный заказ самостоятельны, и для них родитель не спрашивается.
     */
    private ProductionOrder requireParent(Long parentId) {

        if (parentId == null) {
            throw new IllegalArgumentException(
                    "Выберите заказ верхнего уровня, внутрь которого "
                            + "входит заказ на комплектование"
            );
        }

        return resolveParent(parentId);
    }

    /**
     * Родитель будущего заказа: по идентификатору из формы.
     *
     * Пустой идентификатор — заказ верхнего уровня (ЗП), заполненный —
     * заказ-комплектующий (ЗНП). Родителем может быть только действующий
     * выпуск верхнего уровня: вложенность глубже одного уровня разбила бы
     * и дерево выпуска, и смысл связи «изделие ⇄ заказ-комплектующий».
     */
    private ProductionOrder resolveParent(Long parentId) {

        if (parentId == null) {
            return null;
        }

        ProductionOrder parent = productionOrderRepository
                .findDetailedById(parentId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Родительский заказ на производство не найден"
                        )
                );

        if (!parent.isActive()) {
            throw new IllegalStateException(
                    "Заказ «" + parent.getNumber()
                            + "» удалён и не может быть родительским"
            );
        }

        if (parent.isComponent()) {
            throw new IllegalArgumentException(
                    "Заказ «" + parent.getNumber()
                            + "» сам является комплектующим: "
                            + "вложенность глубже одного уровня недопустима"
            );
        }

        return parent;
    }

    /**
     * Уровень дерева (ЗП/ЗНП) задаётся при создании и при правке не
     * меняется: смена родителя перекроила бы всю подчинённую структуру
     * выпуска и связи «изделие ⇄ заказ».
     *
     * Форма правки отдаёт привязанный родительский заказ, поэтому
     * «свой же» родитель считается отсутствием изменения, а не правкой.
     */
    private void assertLevelUnchanged(ProductionOrder order,
                                      Long orderId,
                                      Long requestedParentId) {

        Long boundParentId = order.getParentProductionOrder() == null
                ? null
                : order.getParentProductionOrder().getId();

        if (requestedParentId == null
                || requestedParentId.equals(boundParentId)) {
            return;
        }

        if (requestedParentId.equals(orderId)) {
            throw new IllegalArgumentException(
                    "Заказ «" + order.getNumber()
                            + "» не может быть родителем самого себя"
            );
        }

        throw new IllegalArgumentException(
                "Нельзя менять уровень заказа «" + order.getNumber()
                        + "»: заказ "
                        + (boundParentId == null
                                ? "верхнего уровня"
                                : "комплектующий")
                        + " пересоздаётся заново"
        );
    }

    private CustomerOrder findCustomerOrder(Long customerOrderId) {

        if (customerOrderId == null) {
            throw new IllegalArgumentException(
                    "Выберите заказ покупателя"
            );
        }

        CustomerOrder customerOrder = customerOrderRepository
                .findFullById(customerOrderId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ покупателя не найден"
                        )
                );

        if (!customerOrder.isActive()) {
            throw new IllegalStateException(
                    "Заказ покупателя «" + customerOrder.getNumber()
                            + "» недействующий"
            );
        }

        return customerOrder;
    }

    /**
     * Закрытый статус заказа: выпуск завершён или отменён.
     *
     * Используется обходом дерева, чтобы не переводить завершённые
     * комплектующие обратно в работу.
     */
    private boolean isClosed(ProductionOrderStatus status) {
        return status == ProductionOrderStatus.DONE
                || status == ProductionOrderStatus.CANCELLED;
    }

    /**
     * Заказчик родительского выпуска — источник заказчика для ЗНП.
     *
     * Отдельный метод, а не `parent.getCustomerOrder()` в двух местах:
     * связь lazy, а сессия к моменту использования уже может быть
     * закрыта, поэтому заказчик дочитывается по идентификатору.
     */
    private CustomerOrder requireCustomerOrder(ProductionOrder parent) {

        if (parent.getCustomerOrder() == null) {
            throw new IllegalStateException(
                    "У заказа «" + parent.getNumber()
                            + "» не указан заказ покупателя"
            );
        }

        return findCustomerOrder(parent.getCustomerOrder().getId());
    }

    private ProductionOrder requireActiveOrder(Long id) {
        ProductionOrder order = productionOrderRepository
                .findDetailedById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ на производство не найден"
                        )
                );

        if (!order.isActive()) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» удалён и недоступен для этого действия"
            );
        }

        return order;
    }

    private Operation requireKittingOperation(Long operationId) {

        if (operationId == null) {
            throw new IllegalArgumentException(
                    "Выберите комплектовочную операцию"
            );
        }

        Operation operation = operationRepository
                .findDetailedById(operationId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Операция не найдена"
                        )
                );

        String code = operation.getOperationType() == null
                ? null
                : operation.getOperationType().getCode();

        if (!OperationTypeCode.KITTING.name().equals(code)) {
            throw new IllegalArgumentException(
                    "Заказ на производство можно привязать только "
                            + "к комплектовочной операции"
            );
        }

        return operation;
    }

    /**
     * Изделие группового или ремонтного заказа (миграция V37).
     *
     * Тип изделия проверяется, а не принимается любой: групповой заказ
     * на приборы, заведённый на классификатор ячейки, показал бы в
     * реесте количество приборов там, где их нет. Ремонтные виды
     * ограничены «Ячейкой» — подсборка и ячейка различаются стадией
     * движения, а не типом классификатора (см. QuarantineCellKind),
     * поэтому справочник у них общий, а различие несёт сам вид.
     */
    private ProductClassifier requireClassifier(Long classifierId,
                                                ZnpKind kind) {

        if (classifierId == null) {
            throw new IllegalArgumentException(
                    "Выберите изделие для заказа «"
                            + kind.getDisplayName() + "»"
            );
        }

        ProductClassifier classifier = productClassifierRepository
                .findDetailedById(classifierId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Изделие не найдено"
                        )
                );

        ProductType required = kind.requiredProductType();

        if (required != null
                && classifier.getProductType() != required) {
            throw new IllegalArgumentException(
                    "Для заказа «" + kind.getDisplayName()
                            + "» подходит изделие типа «"
                            + required.getDisplayName()
                            + "», а выбрано «"
                            + (classifier.getProductType() == null
                                    ? "—"
                                    : classifier.getProductType()
                                    .getDisplayName())
                            + "»"
            );
        }

        return classifier;
    }

    /**
     * Количество заказанных изделий.
     *
     * Проверка своя, а не аннотация на форме: поле обязательно только
     * части видов, и @NotNull ломал бы форму комплектования.
     */
    private BigDecimal requireQuantity(BigDecimal quantity, ZnpKind kind) {

        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Укажите количество для заказа «"
                            + kind.getDisplayName() + "»"
            );
        }

        return quantity;
    }

    /**
     * Техпроцесс группового заказа (миграция V40).
     *
     * Обязателен групповым видам: без маршрута непонятно, какие работы
     * выполняют заказ, и в график такую заявку ставить нечем. Проверка
     * держится сервисом, а не аннотацией — комплектованию маршрут не
     * полагается, и @NotNull отклонил бы его корректную форму.
     *
     * Маршрут проверяется на применимость к заказанному изделию:
     * выбор в форме строится по изделию, но изделие могли сменить, пока
     * форма открыта, и заказ с чужим маршрутом повесил бы на изделие
     * работы, которых для него не заводили. Разрешается маршрут,
     * применённый к изделию любым способом (базовый или обычный), —
     * требование «только базовый» лишило бы диспетчера выбора, когда у
     * изделия несколько маршрутов.
     */
    private TechProcess requireTechProcess(Long techProcessId,
                                           ProductClassifier classifier,
                                           ZnpKind kind) {

        if (techProcessId == null) {
            throw new IllegalArgumentException(
                    "Выберите техпроцесс для заказа «"
                            + kind.getDisplayName() + "»"
            );
        }

        TechProcess process = techProcessRepository
                .findHeaderById(techProcessId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Техпроцесс не найден"
                        )
                );

        if (!process.isActive()) {
            throw new IllegalStateException(
                    "Техпроцесс «" + process.getCode()
                            + "» снят из работы"
            );
        }

        if (classifier == null) {
            return process;
        }

        /*
         * Изделие маршрута сравнивается по идентификатору: у справочника
         * own equals сравнивает строку целиком, а здесь нужен именно
         * тот же элемент справочника.
         */
        Long routeClassifierId = process.getProductClassifier() == null
                ? null
                : process.getProductClassifier().getId();

        boolean applicable = classifier.getId().equals(routeClassifierId)
                || !techProcessClassifierRepository
                        .findByTechProcess_IdAndClassifier_Id(
                                process.getId(),
                                classifier.getId()
                        )
                        .isEmpty();

        if (!applicable) {
            throw new IllegalArgumentException(
                    "Техпроцесс «" + process.getCode()
                            + "» не применён к изделию «"
                            + classifier.getDisplayName()
                            + "» — выберите маршрут этого изделия"
            );
        }

        return process;
    }

    /**
     * Вид ЗНП задаётся при создании и при правке не меняется: он
     * определяет, чем задан состав заказа, а прежний состав при смене
     * вида либо теряется, либо повисает незачем.
     */
    private void assertKindUnchanged(ProductionOrder order,
                                     ZnpKind requested) {

        ZnpKind bound = order.getZnpKind() == null
                ? ZnpKind.KITTING
                : order.getZnpKind();

        if (bound == requested) {
            return;
        }

        throw new IllegalArgumentException(
                "Нельзя менять вид заказа «" + order.getNumber()
                        + "»: было «" + bound.getDisplayName()
                        + "», стало «" + requested.getDisplayName()
                        + "» — заказ пересоздаётся заново"
        );
    }

    /**
     * Срок выпуска берётся из привязанного заказа покупателя.
     *
     * В форме выпуска срока нет: он совпадает со сроком поставки
     * и нигде больше не переопределяется, поэтому источником служит
     * только заказ покупателя.
     */
    /*
     * Срок исполнения подтягивается из заказа покупателя — но только
     * когда заказ покупателя есть. Групповой заказ (V38) заводят без
     * заявки, и срока извне у него нет: он остаётся незаполненным и
     * правится в карточке.
     */
    private LocalDate resolveDueDate(CustomerOrder customerOrder) {
        return customerOrder == null
                ? null
                : customerOrder.getDueDate();
    }

    private void validateDates(LocalDate orderDate, LocalDate dueDate) {
        if (orderDate != null
                && dueDate != null
                && dueDate.isBefore(orderDate)) {
            throw new IllegalArgumentException(
                    "Срок исполнения не может быть раньше даты открытия"
            );
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isEmpty(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }

        return ids.stream().allMatch(java.util.Objects::isNull);
    }
}
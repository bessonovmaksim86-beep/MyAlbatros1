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
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ClassifierRouteOptionView;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private final CustomerOrderRepository customerOrderRepository;
    private final OperationRepository operationRepository;
    private final ProductClassifierRepository productClassifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final TechProcessClassifierRepository techProcessClassifierRepository;

    /*
     * Комплектование ЗНП: привязка заказа к уже выпущенным серийникам.
     * Вынесена в отдельный сервис, потому что связь «датчик ⇄ заказ»
     * «многие ко многим» живёт своей таблицей (V20) и нужна и карточке
     * изделия, и карточке заказа.
     */
    private final SerialKittingService serialKittingService;

    private static final String NUMBER_PREFIX = "ПЗ-";

    private static final Pattern NUMBERED =
            Pattern.compile("^ПЗ-(\\d{4})-(\\d+)$");

    /* Верхняя граница пятизначной серии (chk_product_serial_range). */
    private static final int MAX_SERIAL = 99999;

    /**
     * Ближайший свободный номер заказа вида ПЗ-ГГГГ-NNN,
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
        Operation operation = kind.isReleaseBound()
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
         * учёта и автоподбор по шаблону ПЗ-ГГГГ-NNN дал бы номер,
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
            buildRelease(saved, customerOrder);

            return productionOrderRepository.save(saved);
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
        Operation operation = kind.isReleaseBound()
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

        applyStatus(order, ProductionOrderStatus.IN_WORK);

        return order;
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
     * собран вместе с ним, либо не собран. Поэтому перевод ЗП
     * распространяется на детей, а перевод ЗНП не трогает родителя —
     * выпуск может достраиваться остальными комплектующими.
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

        List<ProductionOrder> tree = new ArrayList<>();

        tree.add(order);

        if (order.isRoot()) {
            for (ProductionOrder child
                    : productionOrderRepository
                    .findActiveChildren(order.getId())) {

                /*
                 * Взятие выпуска в работу не «воскрешает» комплектующие,
                 * по которым выпуск уже закрыт или отменён: их статус —
                 * запись о завершённой работе, и перезапись потеряла бы
                 * историю. На закрытие и отмену родителя правило не
                 * распространяется: закрытый выпуск закрывает и свои
                 * комплектующие (требование диспетчера).
                 */
                if (status == ProductionOrderStatus.IN_WORK
                        && isClosed(child.getStatus())) {
                    continue;
                }

                tree.add(child);
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
         * означал бы потерь записи о том, на какой стадии выпуск
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
     * Удаление заказа на производство.
     *
     * Удаляется только заказ, по которому ещё не начат выпуск
     * (статус «Новый»): как только заказ взят в работу или закрыт,
     * история выпуска должна сохраниться.
     *
     * Серийные номера при этом не освобождаются и не переиздаются —
     * реестр неизменяем, занятый номер остаётся занятым.
     */
    @Transactional
    public ProductionOrder delete(Long id) {

        ProductionOrder order = requireActiveOrder(id);

        if (order.getStatus() != ProductionOrderStatus.NEW) {
            throw new IllegalStateException(
                    "Удалить можно только новый заказ, "
                            + "а заказ «" + order.getNumber()
                            + "» в статусе «"
                            + order.getStatus().getDisplayName() + "»"
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

        order.setActive(false);

        return productionOrderRepository.save(order);
    }

    /**
     * Состав выпуска по заказу покупателя: одна строка — один экземпляр.
     *
     * Позиция и заказ покупателя остаются указанными в строке,
     * чтобы выпуск был прослеживаем вплоть до позиции договора.
     */
    private void buildRelease(ProductionOrder order,
                              CustomerOrder customerOrder) {

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

            for (int index = 0; index < quantity; index++) {

                ProductionOrderItem item = new ProductionOrderItem();

                item.setProductClassifier(classifier);
                item.setCustomerOrderItem(customerItem);
                item.setSerial(issueSerial(classifier, order));

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
     * Публичный метод: тот же расчёт нужен предпросмотру состава
     * выпуска в форме создания заказа.
     */
    public int instanceCount(BigDecimal quantity) {
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
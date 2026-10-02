package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CustomerOrderForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Customer;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.CustomerOrderStatus;
import ru.company.production.entity.CustomerOrderType;
import ru.company.production.entity.MeasurementUnit;
import ru.company.production.entity.OrderPriority;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcessType;
import ru.company.production.repository.ClassifierOptionProjection;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.CustomerRepository;
import ru.company.production.repository.OrderPriorityRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.SystemComponentProjection;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.ProductSerialRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Логика заказов покупателя диспетчерского отдела.
 */
@Service
@RequiredArgsConstructor
public class CustomerOrderService {

    private final CustomerOrderRepository customerOrderRepository;
    private final CustomerRepository customerRepository;
    private final ProductClassifierRepository productClassifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final SensorClassifierMatcher sensorClassifierMatcher;
    private final OrderPriorityRepository orderPriorityRepository;
    private final OrderPriorityService orderPriorityService;
    private final SerialIssueService serialIssueService;

    /*
     * Снятие графика при удалении (см. delete). Проверка зависимостей
     * заказу не нужна — она целиком живёт в CustomerOrderRepository, где
     * вопрос один: «можно ли снять заказ с работы». Здесь остаётся
     * только чистка собственных планов заказа, которые больше никогда не
     * будут подтверждены или выполнены.
     */
    private final OrderScheduleRowRepository orderScheduleRowRepository;

    /*
     * Снятие запланированных цеховых операций по изделиям заказа при
     * его удалении. Отдельный репозиторий, а не каскад от серийника:
     * операции снятого с работы заказа висели бы в матрице изделия и в
     * загрузке исполнителя, хотя основания у них больше нет.
     */
    private final ProductSerialOperationRepository
            productSerialOperationRepository;

    /*
     * Очистка резерва серийников, выданных позициям заказа (V45), —
     * те же два вызова, что при правке состава: пересоздание позиций
     * и снятие заказа с работы оставляют после себя висячие резервы.
     */
    private final ProductSerialRepository productSerialRepository;

    private static final String NUMBER_PREFIX = "ЗП-";

    private static final Pattern NUMBERED =
            Pattern.compile("^ЗП-(\\d{4})-(\\d+)$");

    /**
     * Действующие покупатели для выпадающего списка формы.
     */
    public List<Customer> findActiveCustomers() {
        return customerRepository.findByActiveTrueOrderByNameAsc();
    }

    /**
     * Изделия классификатора для выбора в позициях заказа.
     */
    public List<ClassifierOptionProjection> findClassifierOptions() {
        return productClassifierRepository.findAllOptions();
    }

    /**
     * Единицы измерения для позиций заказа.
     */
    public List<MeasurementUnit> findUnits() {
        return Arrays.asList(MeasurementUnit.values());
    }

    /**
     * Статусы заказа для выпадающего списка формы.
     */
    public List<CustomerOrderStatus> findStatuses() {
        return Arrays.asList(CustomerOrderStatus.values());
    }

    /**
     * Приоритеты заказа для выпадающего списка формы.
     *
     * Берутся из справочника: параметры приоритета (запуск, загрузка,
     * переходы, отсечка изготовления) правятся диспетчером, поэтому
     * список не может быть перечислением, зашитым в код.
     */
    public List<OrderPriority> findPriorities() {
        return orderPriorityService.findActive();
    }

    /**
     * Приоритет по умолчанию для формы создания заказа.
     */
    public Long findDefaultPriorityId() {
        return orderPriorityService.findDefaultId();
    }

    /**
     * Виды заказа для выпадающего списка формы.
     */
    public List<CustomerOrderType> findOrderTypes() {
        return Arrays.asList(CustomerOrderType.values());
    }

    /**
     * Типы техпроцесса для выпадающего списка формы.
     */
    public List<TechProcessType> findTechProcessTypes() {
        return Arrays.asList(TechProcessType.values());
    }

    /**
     * Типы изделий для каскадного выбора изделия позиции заказа:
     * сначала тип (прибор/датчик/ячейка/система), затем изделие.
     */
    public List<ProductType> findProductTypes() {
        return Arrays.asList(ProductType.values());
    }

    /**
     * Состав системы для раскрытия полей выбора датчиков и приборов.
     */
    public List<SystemComponentProjection> findSystemComponents(Long systemId) {
        return productClassifierRepository.findSystemComponents(systemId);
    }

    /**
     * Ближайший свободный номер заказа вида ЗП-ГГГГ-NNN,
     * где ГГГГ — текущий год.
     */
    public String nextNumber() {
        int year = LocalDate.now().getYear();

        int max = 0;

        for (CustomerOrder order : customerOrderRepository.findAll()) {
            if (order.getNumber() == null) {
                continue;
            }

            Matcher matcher =
                    NUMBERED.matcher(order.getNumber().trim());

            if (matcher.matches()
                    && Integer.parseInt(matcher.group(1)) == year) {
                try {
                    max = Math.max(max, Integer.parseInt(matcher.group(2)));
                } catch (NumberFormatException ignored) {
                    /*
                     * Номера, созданные вручную и не соответствующие
                     * шаблону, в автогенерации не участвуют.
                     */
                }
            }
        }

        return NUMBER_PREFIX + year + "-"
                + String.format("%0" + Math.max(3, String.valueOf(max + 1).length()) + "d", max + 1);
    }

    /**
     * Создаёт заказ покупателя со всем составом изделий.
     *
     * Позиции нумеруются автоматически (CustomerOrder.addItem).
     */
    @Transactional
    public CustomerOrder create(CustomerOrderForm form, AppUser author) {

        Customer customer = customerRepository
                .findById(form.getCustomerId())
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Покупатель не найден"
                        )
                );

        if (!customer.isActive()) {
            throw new IllegalStateException(
                    "Покупатель «" + customer.getName()
                            + "» недействующий"
            );
        }

        if (form.getOrderDate() != null
                && form.getDueDate() != null
                && form.getDueDate().isBefore(form.getOrderDate())) {
            throw new IllegalArgumentException(
                    "Срок исполнения не может быть раньше даты заказа"
            );
        }

        String number = form.getNumber() == null
                ? ""
                : form.getNumber().trim();

        if (number.isBlank()) {
            number = nextNumber();
        }

        if (customerOrderRepository.existsByNumber(number)) {
            throw new IllegalArgumentException(
                    "Заказ с номером «" + number + "» уже существует"
            );
        }

        CustomerOrder order = new CustomerOrder();

        order.setNumber(number);
        order.setCustomer(customer);
        order.setOrderDate(form.getOrderDate());
        order.setDueDate(form.getDueDate());
        order.setContractNumber(blankToNull(form.getContractNumber()));
        order.setNote(blankToNull(form.getNote()));

        /*
         * Ссылки на Б24 необязательны: пустое значение сохраняется
         * как null, чтобы в просмотре поле корректно показывало
         * «не указано», а не битую ссылку.
         */
        order.setB24OrderUrl(blankToNull(form.getB24OrderUrl()));
        order.setB24ChecklistUrl(blankToNull(form.getB24ChecklistUrl()));
        order.setPriority(resolvePriority(form.getPriorityId()));

        /*
         * Вид заказа: при пропуске заказ считается производственным —
         * это основной сценарий диспетчера.
         */
        CustomerOrderType orderType =
                CustomerOrderType.orDefault(form.getOrderType());

        order.setOrderType(orderType);

        /*
         * Статус задаётся пользователем; при пропуске заказ
         * поступает как новый — это точка входа всего цикла.
         */
        order.setStatus(form.getStatus() == null
                ? CustomerOrderStatus.NEW
                : form.getStatus());

        order.setCreatedBy(author);
        order.setActive(true);

        for (CustomerOrderForm.CustomerOrderItemForm itemForm
                : form.getItems()) {

            ItemResolution resolution = resolveItem(itemForm);

            ProductClassifier classifier = resolution.classifier();

            TechProcessType techProcessType =
                    TechProcessType.orDefault(
                            itemForm.getTechProcessType()
                    );

            /*
             * «Базовое» — это текущий техпроцесс, привязанный к изделию.
             * Для производственного заказа позиция не может ссылаться
             * на отсутствующий маршрут, поэтому при его отсутствии
             * заказ не сохраняется и пользователь получает понятную
             * ошибку с кодом изделия.
             */
            if (orderType == CustomerOrderType.PRODUCTION
                    && techProcessType == TechProcessType.BASIC
                    && techProcessRepository.countActiveForClassifier(
                            classifier.getId()) == 0) {
                throw new IllegalArgumentException(
                        "Изделию «" + classifier.getCode()
                                + "» не назначен техпроцесс. "
                                + "Разработайте техпроцесс или примените "
                                + "существующий к этому изделию."
                );
            }

            CustomerOrderItem item = new CustomerOrderItem();

            item.setProductClassifier(classifier);
            item.setFullName(resolution.fullName());
            item.setQuantity(itemForm.getQuantity());
            item.setUnit(parseUnit(itemForm.getUnit()));
            item.setTechProcessType(techProcessType);
            item.setDoneQuantity(BigDecimal.ZERO);
            item.setDueDate(itemForm.getDueDate());
            item.setNote(blankToNull(itemForm.getNote()));

            order.addItem(item);
        }

        CustomerOrder saved = customerOrderRepository.save(order);

        if (saved.getOrderType() == CustomerOrderType.PRODUCTION) {
            reserveSerials(saved);
        }

        return saved;
    }

    /**
     * Обновляет существующий заказ покупателя вместе с составом.
     *
     * Позиции пересоздаются целиком: форма заказа всегда отдаёт
     * плотной список (items[0..n-1]), поэтому проще очистить
     * состав и заново вызовить {@link CustomerOrder#addItem},
     * чем сверять строки по идентификаторам.
     */
    @Transactional
    public CustomerOrder update(Long id, CustomerOrderForm form) {

        CustomerOrder order = customerOrderRepository
                .findFullById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ покупателя не найден"
                        )
                );

        /*
         * Снятие резервов — безусловно: заказ мог сменить вид с
         * производства на ремонт, а старые резервы обязаны уйти до
         * пересборки состава, иначе внешний ключ
         * product_serials.customer_order_item_id не отдаст удаляемые
         * позиции. clearAutomatically в этих запросах чистит контекст,
         * поэтому заказ перезагружается до изменения реквизитов.
         */
        productSerialRepository.unlinkReleasedByCustomerOrderId(id);
        productSerialRepository.deleteFreeByCustomerOrderId(id);

        order = customerOrderRepository
                .findFullById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ покупателя не найден"
                        )
                );

        Customer customer = customerRepository
                .findById(form.getCustomerId())
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Покупатель не найден"
                        )
                );

        if (!customer.isActive()) {
            throw new IllegalStateException(
                    "Покупатель «" + customer.getName()
                            + "» недействующий"
            );
        }

        if (form.getOrderDate() != null
                && form.getDueDate() != null
                && form.getDueDate().isBefore(form.getOrderDate())) {
            throw new IllegalArgumentException(
                    "Срок исполнения не может быть раньше даты заказа"
            );
        }

        String number = form.getNumber() == null
                ? ""
                : form.getNumber().trim();

        if (number.isBlank()) {
            number = order.getNumber();
        }

        if (customerOrderRepository.existsByNumberAndIdNot(number, id)) {
            throw new IllegalArgumentException(
                    "Заказ с номером «" + number + "» уже существует"
            );
        }

        order.setNumber(number);
        order.setCustomer(customer);
        order.setOrderDate(form.getOrderDate());
        order.setDueDate(form.getDueDate());
        order.setContractNumber(blankToNull(form.getContractNumber()));
        order.setNote(blankToNull(form.getNote()));
        order.setB24OrderUrl(blankToNull(form.getB24OrderUrl()));
        order.setB24ChecklistUrl(blankToNull(form.getB24ChecklistUrl()));
        order.setPriority(resolvePriority(form.getPriorityId()));
        order.setOrderType(CustomerOrderType.orDefault(form.getOrderType()));

        if (form.getStatus() != null) {
            order.setStatus(form.getStatus());
        }

        CustomerOrderType orderType = order.getOrderType();

        /*
         * Состав пересоздаётся: старые позиции удаляются за счёт
         * orphanRemoval, новые получают порядковые номера заново.
         */
        order.clearItems();

        for (CustomerOrderForm.CustomerOrderItemForm itemForm
                : form.getItems()) {

            ItemResolution resolution = resolveItem(itemForm);

            ProductClassifier classifier = resolution.classifier();

            TechProcessType techProcessType =
                    TechProcessType.orDefault(
                            itemForm.getTechProcessType()
                    );

            if (orderType == CustomerOrderType.PRODUCTION
                    && techProcessType == TechProcessType.BASIC
                    && techProcessRepository.countActiveForClassifier(
                            classifier.getId()) == 0) {
                throw new IllegalArgumentException(
                        "Изделию «" + classifier.getCode()
                                + "» не назначен техпроцесс. "
                                + "Разработайте техпроцесс или примените "
                                + "существующий к этому изделию."
                );
            }

            CustomerOrderItem item = new CustomerOrderItem();

            item.setProductClassifier(classifier);
            item.setFullName(resolution.fullName());
            item.setQuantity(itemForm.getQuantity());
            item.setUnit(parseUnit(itemForm.getUnit()));
            item.setTechProcessType(techProcessType);
            item.setDoneQuantity(BigDecimal.ZERO);
            item.setDueDate(itemForm.getDueDate());
            item.setNote(blankToNull(itemForm.getNote()));

            order.addItem(item);
        }

        CustomerOrder saved = customerOrderRepository.save(order);

        if (saved.getOrderType() == CustomerOrderType.PRODUCTION) {
            reserveSerials(saved);
        }

        return saved;
    }

    /**
     * Планирование заказа: заказ берётся в работу.
     *
     * Закрытые (исполненные, отменённые, удалённые) заказы
     * обратно в работу не переводятся.
     */
    @Transactional
    public CustomerOrder plan(Long id) {

        CustomerOrder order = customerOrderRepository
                .findHeaderById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ покупателя не найден"
                        )
                );

        if (!order.isActive()) {
            throw new IllegalStateException(
                    "Неактивный заказ нельзя спланировать"
            );
        }

        CustomerOrderStatus status = order.getStatus();

        if (status == CustomerOrderStatus.DONE
                || status == CustomerOrderStatus.CANCELLED
                || status == CustomerOrderStatus.DELETED) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» уже закрыт и не может быть запланирован"
            );
        }

        /*
         * Правила приоритета проверяются до перевода в работу: если
         * отсечка изготовления уже прошла, заказ физически не успеет
         * к сроку, и брать его в работу смысла нет — диспетчеру нужно
         * сменить приоритет на менее жёсткий.
         */
        orderPriorityService.checkPlanningPossible(order);

        order.setStatus(CustomerOrderStatus.IN_WORK);

        return customerOrderRepository.save(order);
    }

    /**
     * Помечает заказ покупателя удалённым.
     *
     * Заказ не стирается из базы, а снимается с работы (active = false)
     * и получает статус «Удалён»: на него ссылаются выпуск, график цеха
     * и реестр серийников, поэтому физическое удаление разорвало бы
     * прослеживаемость — ровно ради этого статуса DELETED и введён (V13).
     * Снятие с работы выводит заказ из всех списков и счётчиков: они
     * отобраны по active = true.
     *
     * УДАЛЯТЬ МОЖНО ТОЛЬКО ЗАКАЗ БЕЗ ПРОВЕДЁННЫХ ОПЕРАЦИЙ. Проведённой
     * считается любая работа, зафиксированная по изделиям заказа:
     *
     *   1) позиция, по которой отмечено готовое количество — изделия
     *      уже принимали;
     *   2) строка графика, закрытая исполнителем
     *      (order_schedule_rows.completed_at, V44);
     *   3) действующий заказ на производство — заказ уже породил
     *      выпуск, и удалять его основание нельзя;
     *   4) факт цеховой операции по изделиям заказа
     *      (product_serial_operations.actual_date, V19) — заказ мог
     *      работать и без графика.
     *
     * ВСЁ ЗАПЛАНИРОВАННОЕ ПО ЗАКАЗУ СНИМАЕТСЯ ВМЕСТЕ С НИМ. План — это
     * намерение, а не событие, и после снятия заказа он обязан
     * исчезнуть, иначе цех видел бы работу без основания:
     *
     *   * строки графика обеих стадий (DRAFT и CONFIRMED) — те же два
     *     вызова, что при перепланировании в OrderSchedulingService.
     *     Внесённые в план строки уходят наравне с черновиком: их
     *     человеко-часы учитываются в загрузке этапов (V43), и
     *     оставленный план ложно занимал бы людей и отклонял расчёт
     *     следующего заказа. Проведённых строк здесь быть не может —
     *     заказ с ними отсекается проверкой выше;
     *   * запланированные цеховые операции изделий заказа
     *     (product_serial_operations с пустым actual_date) — иначе они
     *     висели бы в матрице изделия и в загрузке исполнителя;
     *   * резерв номеров, выданных самому заказу (V45).
     *
     * Факты (проведённые строки, закрытые операции, реестр серийников)
     * не трогаются: они переживают заказ.
     */
    @Transactional
    public CustomerOrder delete(Long id) {

        CustomerOrder order = customerOrderRepository
                .findHeaderById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ покупателя не найден"
                        )
                );

        if (!order.isActive()) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber() + "» уже удалён"
            );
        }

        assertNoPerformedOperations(order);

        /*
         * График заказа снимается целиком — и черновик, и внесённые в
         * план строки. Порядок вызовов (сначала DRAFT, потом CONFIRMED)
         * повторяет OrderSchedulingService#confirm, где той же парой
         * запросов пересобирают план перед новым расчётом.
         */
        orderScheduleRowRepository.deleteByOrderIdAndStatus(
                id,
                ScheduleRowStatus.DRAFT
        );

        orderScheduleRowRepository.deleteByOrderIdAndStatus(
                id,
                ScheduleRowStatus.CONFIRMED
        );

        /*
         * Запланированные цеховые операции изделий снимаются ДО работы
         * с резервом: unlinkReleasedByCustomerOrderId обнуляет
         * customer_order_item_id, и после него вторая ветвь запроса
         * (изделие зарезервировано позицией заказа) уже ничего бы не
         * нашла.
         */
        productSerialOperationRepository
                .deletePlannedByCustomerOrderId(id);

        /*
         * Резерв номеров, выданных заказу заранее, освобождается;
         * номера, уже подхваченные выпуском, только отвязываются от
         * позиций — реестр серийников неизменяем (те же вызовы, что
         * при пересоздании состава заказа).
         */
        productSerialRepository.deleteFreeByCustomerOrderId(id);
        productSerialRepository.unlinkReleasedByCustomerOrderId(id);

        /*
         * Заказ перечитывается: все запросы выше идут с
         * clearAutomatically и очищают контекст, поэтому прочитанный
         * внизу объект был бы detached (тот же приём, что при
         * пересборке состава в update).
         */
        order = customerOrderRepository
                .findHeaderById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Заказ покупателя не найден"
                        )
                );

        order.setStatus(CustomerOrderStatus.DELETED);
        order.setActive(false);

        return customerOrderRepository.save(order);
    }

    /**
     * Проверка «заказ с проведёнными операциями удалить нельзя».
     *
     * Порядок идёт от признака, который диспетчер видит прямо в заказе,
     * к тому, что живёт в других модулях: отказ обязан назвать причину
     * (готовая позиция, график, выпуск, операции цеха), а не
     * ограничиваться безличным «нельзя» — иначе непонятно, что именно
     * разбирать перед удалением.
     */
    private void assertNoPerformedOperations(CustomerOrder order) {

        Long id = order.getId();

        String number = order.getNumber();

        /*
         * 1) Позиция, по которой уже приняли изделия.
         */
        if (customerOrderRepository.existsItemWithDoneQuantity(id)) {
            throw new IllegalStateException(
                    "Заказ «" + number + "» удалить нельзя: по его "
                            + "позициям уже отмечено готовое количество"
            );
        }

        /*
         * 2) Строка графика, проведённая исполнителем.
         */
        if (customerOrderRepository.existsCompletedScheduleRow(id)) {
            throw new IllegalStateException(
                    "Заказ «" + number + "» удалить нельзя: по его "
                            + "графику уже проведены операции — "
                            + "сначала снимите проведения"
            );
        }

        /*
         * 3) Действующий заказ на производство этого заказа.
         */
        if (customerOrderRepository.existsActiveProductionOrder(id)) {
            throw new IllegalStateException(
                    "Заказ «" + number + "» удалить нельзя: по нему "
                            + "создан заказ на производство — сначала "
                            + "отмените или удалите его"
            );
        }

        /*
         * 4) Факт цеховой операции по изделиям заказа: как выпущенным
         * заказом на производство, так и зарезервированным самим
         * заказом покупателя.
         */
        if (customerOrderRepository.existsPerformedSerialOperation(id)) {
            throw new IllegalStateException(
                    "Заказ «" + number + "» удалить нельзя: по его "
                            + "изделиям уже проведены цеховые операции"
            );
        }
    }

    /**
     * Приоритет заказа по идентификатору из формы.
     *
     * Пустое значение допускается только ради заказов, заведённых до
     * справочника: им выдаётся «обычный» — ровно то же, что раньше
     * делал orDefault() для пустой строки кода.
     */
    private OrderPriority resolvePriority(Long priorityId) {
        if (priorityId == null) {
            OrderPriority fallback = orderPriorityService.findDefault();

            if (fallback == null) {
                throw new IllegalArgumentException(
                        "Выберите приоритет: в справочнике нет ни одного "
                                + "действующего правила планирования"
                );
            }

            return fallback;
        }

        OrderPriority priority = orderPriorityRepository
                .findById(priorityId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Приоритет не найден"
                ));

        if (!priority.isActive()) {
            throw new IllegalStateException(
                    "Приоритет «" + priority.getName()
                            + "» снят с использования, выберите другой"
            );
        }

        return priority;
    }

    /**
     * Изделие позиции вместе с полным наименованием, которое
     * сохранится в позицию (для датчика — строка оператора).
     */
    private record ItemResolution(
            ProductClassifier classifier,
            String fullName
    ) {
    }

    /**
     * Определяет изделие позиции.
     *
     * Для датчика изделие не выбирается из списка: диспетчер вносит
     * полное обозначение вручную, а классификатор подбирается по роду
     * и значимым параметрам ({@link SensorClassifierMatcher}).
     * Для остальных типов по-прежнему используется выбор из
     * классификатора.
     *
     * Если идентификатор прислан вместе с наименованием (например,
     * после неудачной отправки формы), доверие отдаётся идентификатору:
     * он выбран пользователем осознанно, а подбор мог не сработать
     * из-за опечатки, которую пользователь уже исправил выбором из списка.
     */
    private ItemResolution resolveItem(
            CustomerOrderForm.CustomerOrderItemForm itemForm
    ) {

        String fullName = blankToNull(itemForm.getFullName());

        if (itemForm.getProductClassifierId() != null) {
            return new ItemResolution(
                    productClassifierRepository
                            .findById(itemForm.getProductClassifierId())
                            .orElseThrow(() ->
                                    new EntityNotFoundException(
                                            "Изделие классификатора не найдено"
                                    )
                            ),
                    fullName
            );
        }

        boolean sensor = itemForm.getProductType() == ProductType.SENSOR;

        if (!sensor) {
            throw new IllegalArgumentException(
                    "Выберите изделие из классификатора: для изделий, "
                            + "отличных от датчика, ручное наименование "
                            + "не применяется."
            );
        }

        if (fullName == null) {
            throw new IllegalArgumentException(
                    "Укажите полное наименование датчика, например "
                            + "«Датчик уровня ультразвуковой ДУУ2М-12-1-…»"
            );
        }

        SensorClassifierMatcher.Match match =
                sensorClassifierMatcher.match(fullName);

        return new ItemResolution(match.classifier(), fullName);
    }

    /**
     * Единица измерения позиции: пустое значение — «шт.».
     */
    private MeasurementUnit parseUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return MeasurementUnit.PCS;
        }

        try {
            return MeasurementUnit.valueOf(
                    unit.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Недопустимая единица измерения: " + unit
            );
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Резервирует серийники под позиции производственного заказа:
     * по одному на экземпляр. Количество экземпляров и строки выпуска
     * считаются одним правилом (SerialNumbering.instancesOf), чтобы
     * номер не появился без строки выпуска.
     */
    private void reserveSerials(CustomerOrder order) {

        for (CustomerOrderItem item : order.getItems()) {

            /*
             * taken — отдельный набор на позицию: у разных позиций
             * разные области нумерации, общий набор ложно «занимал» бы
             * одинаковые серии разных изделий.
             */
            Set<Integer> taken = new HashSet<>();

            int instances =
                    SerialNumbering.instancesOf(item.getQuantity());

            for (int index = 0; index < instances; index++) {
                serialIssueService.reserveForItem(
                        item.getProductClassifier(), item, taken
                );
            }
        }
    }

    /**
     * Незакрытые выпуском резервы заказа — строка «зарезервировано N»
     * в карточке. Запрос детальный (open-in-view выключен), строка
     * списка печатает полный номер.
     */
    public List<ProductSerial> getReservedSerials(Long orderId) {

        return productSerialRepository
                .findFreeDetailsByCustomerOrderId(orderId);
    }
}
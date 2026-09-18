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
import ru.company.production.entity.TechProcessType;
import ru.company.production.repository.ClassifierOptionProjection;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.CustomerRepository;
import ru.company.production.repository.OrderPriorityRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SystemComponentProjection;
import ru.company.production.repository.TechProcessRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
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

        return customerOrderRepository.save(order);
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

        return customerOrderRepository.save(order);
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
}

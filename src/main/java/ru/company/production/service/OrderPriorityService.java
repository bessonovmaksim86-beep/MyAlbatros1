package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.OrderPriorityForm;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OrderPriority;
import ru.company.production.repository.OrderPriorityRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Справочник приоритетов планирования.
 *
 * Приоритет — не просто метка срочности, а набор правил запуска заказа:
 * через сколько дней стартовать, до какой загрузки участка назначать
 * работу, сколько длятся переходы между операциями и сколько рабочих
 * дней остаётся на изготовление перед отгрузкой. От последнего правила
 * зависит возможность планирования, поэтому проверка живёт здесь же —
 * рядом с тем местом, где параметр задаётся.
 */
@Service
@RequiredArgsConstructor
public class OrderPriorityService {

    private final OrderPriorityRepository orderPriorityRepository;
    private final WorkingDaysCalculator workingDays;

    private static final String CODE_PREFIX = "ПР-";

    /**
     * Приоритет по умолчанию для нового заказа: «обычный» — заказ
     * выполняется в общей очерёдности.
     */
    private static final String DEFAULT_CODE = "ORDINARY";

    @Transactional(readOnly = true)
    public List<OrderPriority> findAll() {
        return orderPriorityRepository.findAllByOrderByIdAsc();
    }

    /**
     * Действующие приоритеты для выпадающего списка формы заказа.
     */
    @Transactional(readOnly = true)
    public List<OrderPriority> findActive() {
        return orderPriorityRepository.findByActiveTrueOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return orderPriorityRepository.findByActiveTrueOrderByIdAsc().size();
    }

    @Transactional(readOnly = true)
    public OrderPriority get(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор приоритета"
            );
        }

        return orderPriorityRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Приоритет с идентификатором " + id + " не найден"
                ));
    }

    /**
     * Приоритет по умолчанию: «обычный». Если справочник пуст
     * (миграция не выполнена или правила удалены), возвращается null —
     * вызывающий код сам решит, ругаться или пропустить поле.
     */
    @Transactional(readOnly = true)
    public OrderPriority findDefault() {
        return orderPriorityRepository.findByCode(DEFAULT_CODE).orElse(null);
    }

    /**
     * Идентификатор приоритета по умолчанию — для формы создания заказа.
     */
    @Transactional(readOnly = true)
    public Long findDefaultId() {
        OrderPriority priority = findDefault();

        return priority == null ? null : priority.getId();
    }

    /**
     * Форма правки: значения берутся из сохранённого правила.
     */
    @Transactional(readOnly = true)
    public OrderPriorityForm getForm(Long id) {
        OrderPriority priority = get(id);

        OrderPriorityForm form = new OrderPriorityForm();

        form.setName(priority.getName());
        form.setStartDays(priority.getStartDays());
        form.setMaxLoadPercent(priority.getMaxLoadPercent());
        form.setTransitionDays(priority.getTransitionDays());
        form.setMaxManufacturingDays(priority.getMaxManufacturingDays());
        form.setBadgeClass(priority.getBadgeClass());

        return form;
    }

    @Transactional
    public OrderPriority create(OrderPriorityForm form) {
        OrderPriority priority = new OrderPriority();

        priority.setCode(nextFreeCode());
        priority.setBadgeClass(normalizeBadge(form.getBadgeClass()));

        applyForm(priority, form);

        try {
            return orderPriorityRepository.saveAndFlush(priority);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "Приоритет «" + priority.getName() + "» уже существует",
                    exception
            );
        }
    }

    @Transactional
    public OrderPriority update(Long id, OrderPriorityForm form) {
        OrderPriority priority = get(id);

        applyForm(priority, form);

        if (form.getBadgeClass() != null) {
            priority.setBadgeClass(normalizeBadge(form.getBadgeClass()));
        }

        return orderPriorityRepository.save(priority);
    }

    /**
     * Помечает приоритет недействующим.
     *
     * Удаление здесь сознательно заменено архивированием: на приоритет
     * ссылаются заказы покупателя (customer_orders.priority_id NOT NULL),
     * поэтому физическое удаление разорвало бы историю уже выпущенных
     * заказов. Недействующий приоритет исчезает из формы создания заказа,
     * но остаётся в ранее оформленных заказах.
     */
    @Transactional
    public void deactivate(Long id) {
        OrderPriority priority = get(id);

        if (!priority.isActive()) {
            throw new IllegalArgumentException(
                    "Приоритет «" + priority.getName()
                            + "» уже в архиве"
            );
        }

        priority.setActive(false);

        orderPriorityRepository.save(priority);
    }

    /**
     * Возвращает приоритет в строй.
     */
    @Transactional
    public void activate(Long id) {
        OrderPriority priority = get(id);

        priority.setActive(true);

        orderPriorityRepository.save(priority);
    }

    /**
     * Проверка возможности планирования заказа по его приоритету.
     *
     * Отсечка изготовления отсчитывается назад от срока исполнения:
     * при сроке 23.09 и отсечке 3 р. д. минимальная дата изготовления —
     * 20.09. Начать заказ позже этой даты нельзя — он физически не
     * успеет к сроку, и диспетчеру приходится менять приоритет
     * на менее жёсткий.
     *
     * Заказ без срока исполнения проверить нельзя: считать отсечку
     * не от чего, поэтому планирование разрешается.
     *
     * @throws IllegalStateException если планирование невозможно
     */
    public void checkPlanningPossible(CustomerOrder order) {
        OrderPriority priority = order.getPriority();

        LocalDate dueDate = order.getDueDate();

        if (priority == null
                || priority.getMaxManufacturingDays() == null
                || dueDate == null) {
            return;
        }

        LocalDate latestStart = workingDays.minus(
                dueDate,
                priority.getMaxManufacturingDays()
        );

        if (LocalDate.now().isAfter(latestStart)) {
            throw new IllegalStateException(
                    "Планирование по приоритету «" + priority.getName()
                            + "» невозможно, поменяйте приоритет: "
                            + "на изготовление осталось меньше "
                            + priority.getMaxManufacturingDays()
                            + " раб. д. — приступить нужно было не позднее "
                            + latestStart.format(RU_DATE)
                            + " при сроке "
                            + dueDate.format(RU_DATE)
            );
        }
    }

    private static final java.time.format.DateTimeFormatter RU_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private void applyForm(OrderPriority priority, OrderPriorityForm form) {
        String name = form.getName() == null
                ? ""
                : form.getName().trim().replaceAll("\\s+", " ");

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "Введите наименование приоритета"
            );
        }

        if (name.length() > 100) {
            throw new IllegalArgumentException(
                    "Наименование приоритета не должно превышать 100 символов"
            );
        }

        boolean duplicate = priority.getId() == null
                ? orderPriorityRepository.existsByNameIgnoreCase(name)
                : orderPriorityRepository
                .existsByNameIgnoreCaseAndIdNot(name, priority.getId());

        if (duplicate) {
            throw new IllegalArgumentException(
                    "Приоритет «" + name + "» уже существует"
            );
        }

        requireRange(
                form.getStartDays(),
                0,
                365,
                "Укажите запуск — количество дней до первых операций"
        );
        requireRange(
                form.getMaxLoadPercent(),
                1,
                100,
                "Допустимая загруженность должна быть от 1 до 100 процентов"
        );
        requireRange(
                form.getTransitionDays(),
                0,
                365,
                "Укажите время межоперационных переходов в рабочих днях"
        );
        requireRange(
                form.getMaxManufacturingDays(),
                0,
                365,
                "Укажите максимальную дату изготовления в рабочих днях"
        );

        priority.setName(name);
        priority.setStartDays(form.getStartDays());
        priority.setMaxLoadPercent(form.getMaxLoadPercent());
        priority.setTransitionDays(form.getTransitionDays());
        priority.setMaxManufacturingDays(form.getMaxManufacturingDays());
    }

    private static void requireRange(
            Integer value,
            int min,
            int max,
            String message
    ) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String normalizeBadge(String badgeClass) {
        if (badgeClass == null || badgeClass.isBlank()) {
            return "priority-ordinary";
        }

        String normalized = badgeClass.trim();

        return normalized.length() > 40
                ? normalized.substring(0, 40)
                : normalized;
    }

    /**
     * Свободный код вида ПР-001: расчёт повторяется до первого
     * незанятого значения — часть кодов могла быть заведена вручную.
     */
    private String nextFreeCode() {
        List<String> codes =
                new ArrayList<>(orderPriorityRepository.findAllCodes());

        int max = 0;

        for (String code : codes) {
            if (code == null || !code.startsWith(CODE_PREFIX)) {
                continue;
            }

            try {
                max = Math.max(
                        max,
                        Integer.parseInt(code.substring(CODE_PREFIX.length()))
                );
            } catch (NumberFormatException ignored) {
                /*
                 * Коды, не соответствующие шаблону (в том числе перенесённые
                 * из прежнего перечисления), в автогенерации не участвуют.
                 */
            }
        }

        int next = max + 1;

        return CODE_PREFIX + String.format(
                "%0" + Math.max(3, String.valueOf(next).length()) + "d",
                next
        );
    }
}
package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CapacityForecastForm;
import ru.company.production.dto.CapacityForecastResult;
import ru.company.production.dto.ForecastOperationRow;
import ru.company.production.dto.ForecastSummary;
import ru.company.production.dto.ForecastWeekRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.HolidayDay;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrderPriority;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessClassifier;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.HolidayDayRepository;
import ru.company.production.repository.OrderPriorityRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Плановый расчёт выполнения — калькулятор возможностей цехов.
 *
 * Диспетчер выбирает изделие, количество, дату начала и приоритет,
 * а сервис прогоняет маршрут операций по мощностям: для каждой
 * операции берёт цех-хозяина, считает мощность (сотрудники × 8 ч,
 * ограниченную допустимой загрузкой приоритета) и свободное время
 * с учётом уже внесённого плана (CONFIRMED строки графика), затем
 * размещает часы по рабочим дням.
 *
 * ПРИОРИТЕТ. Параметры берутся из справочника order_priorities —
 * того же, что использует планировщик заказов (OrderSchedulingService):
 *
 *   start_days             — запуск: через сколько рабочих дней после
 *                            выбранной даты начинать первые операции;
 *   transition_days        — межоперационные переходы: пауза между
 *                            окончанием операции и началом следующей;
 *   max_load_percent       — допустимая загрузка цеха: доля мощности,
 *                            которую можно занять работой;
 *   max_manufacturing_days — отсечка изготовления: если расчётный срок
 *                            превышает её, заказ не укладывается в срок.
 *
 * ДОСТОВЕРНОСТЬ СРОКА. Если операция не привязана к цеху или в цехе
 * нет сотрудников, срок определить нельзя: такая операция не получает
 * дату, а сводка возвращает finishDate = null. Иначе диспетчер прочитал
 * бы «заказ будет выполнен сегодня» там, где мощность нулевая.
 *
 * ОГРАНИЧЕНИЯ. Прогноз не учитывает отсутствия сотрудников по
 * календарю (отпуска, больничные) — это оценка «сверху», а не
 * обещание срока. Пропускная способность оборудования
 * (tech_process_operations.daily_limit) учитывается как потолок
 * часов в день: daily_limit × labor_hours.
 */
@Service
@RequiredArgsConstructor
public class CapacityForecastService {

    /** Рабочий день в часах — та же константа, что в планировщике. */
    private static final BigDecimal WORKDAY_HOURS = new BigDecimal("8");

    /** Горизонт поиска свободного дня — защита от бесконечного цикла. */
    private static final int MAX_DAYS_AHEAD = 730;

    /** Сколько недель показывать в недельном плане. */
    private static final int MAX_PLAN_WEEKS = 26;

    /** Приоритет по умолчанию, если диспетчер не выбрал другой. */
    private static final String DEFAULT_PRIORITY_CODE = "ORDINARY";

    private final TechProcessRepository techProcessRepository;
    private final TechProcessClassifierRepository
            techProcessClassifierRepository;
    private final ProductClassifierRepository
            productClassifierRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final OrderPriorityRepository orderPriorityRepository;
    private final HolidayDayRepository holidayDayRepository;
    private final UserRepository userRepository;

    /**
     * Расчёт возможности изготовления.
     *
     * @param form       изделие, количество, дата начала, приоритет
     * @param ignoreLoad не учитывать уже внесённый план (CONFIRMED)
     */
    @Transactional(readOnly = true)
    public CapacityForecastResult calculate(
            CapacityForecastForm form,
            boolean ignoreLoad
    ) {
        int quantity = Math.max(1, form.quantity());
        LocalDate requestedStart = form.startDate() != null
                ? form.startDate()
                : LocalDate.now();

        List<String> warnings = new ArrayList<>();

        ProductClassifier classifier = form.classifierId() == null
                ? null
                : productClassifierRepository
                    .findById(form.classifierId())
                    .orElse(null);

        if (classifier == null) {
            return emptyResult(quantity, requestedStart, warnings,
                    "Изделие не найдено — выберите его из справочника.");
        }

        TechProcess process = resolveProcess(classifier, warnings);

        if (process == null) {
            return emptyResult(quantity, requestedStart, warnings,
                    "Для изделия «" + classifier.getDisplayName()
                            + "» не задан техпроцесс. "
                            + "Сначала создайте маршрут операций.");
        }

        List<TechProcessOperation> route = orderedRoute(process);

        if (route.isEmpty()) {
            return emptyResult(quantity, requestedStart, warnings,
                    "Техпроцесс " + process.getCode()
                            + " не содержит операций.");
        }

        /*
         * Приоритет: правила запуска, переходов и загрузки.
         */
        OrderPriority priority = resolvePriority(form.priorityId(), warnings);

        int startDays = priority == null || priority.getStartDays() == null
                ? 0
                : Math.max(0, priority.getStartDays());
        int transitionDays =
                priority == null || priority.getTransitionDays() == null
                        ? 0
                        : Math.max(0, priority.getTransitionDays());
        int maxLoadPercent =
                priority == null || priority.getMaxLoadPercent() == null
                        ? 100
                        : Math.max(1, Math.min(100,
                                priority.getMaxLoadPercent()));

        /*
         * Календарь рабочих дней: выходные и праздники.
         */
        WorkingCalendar calendar = loadCalendar(
                requestedStart,
                requestedStart.plusDays(MAX_DAYS_AHEAD)
        );

        LocalDate startDate = plusWorkingDays(
                requestedStart, startDays, calendar);

        /*
         * Мощность цехов и текущая загрузка.
         */
        Map<Long, Integer> employeesByUnit = countEmployeesByUnit();

        Map<Long, Map<LocalDate, BigDecimal>> occupied =
                ignoreLoad
                        ? Map.of()
                        : loadConfirmed(
                                startDate,
                                startDate.plusDays(MAX_DAYS_AHEAD)
                        );

        /*
         * Размещение операций.
         */
        Map<Long, LocalDate> finishByNode = new HashMap<>();
        List<ForecastOperationRow> rows = new ArrayList<>();

        BigDecimal totalLabor = BigDecimal.ZERO;
        LocalDate overallFinish = null;
        int missingCapacity = 0;

        for (TechProcessOperation node : route) {
            Operation operation = node.getOperation();

            BigDecimal labor = node.getLaborHours() == null
                    ? BigDecimal.ZERO
                    : node.getLaborHours();
            BigDecimal machine = node.getMachineHours() == null
                    ? BigDecimal.ZERO
                    : node.getMachineHours();

            BigDecimal hoursForQty = labor
                    .multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);

            Long unitId = operation.getOrganizationUnit() != null
                    ? operation.getOrganizationUnit().getId()
                    : null;
            String unitName = operation.getOrganizationUnit() != null
                    ? operation.getOrganizationUnit().getName()
                    : "Не назначен";
            String serviceCode = operation.getOrganizationUnit() != null
                    && operation.getOrganizationUnit().getService() != null
                    ? operation.getOrganizationUnit().getService().getCode()
                    : null;

            int employees = unitId == null
                    ? 0
                    : employeesByUnit.getOrDefault(unitId, 0);

            /*
             * Мощность цеха: люди × 8 ч, ограниченная допустимой
             * загрузкой приоритета. 100 % — полная мощность,
             * 90 % — пятая часть дня остаётся на прочую работу.
             */
            BigDecimal dailyCapacity = WORKDAY_HOURS
                    .multiply(BigDecimal.valueOf(employees))
                    .multiply(BigDecimal.valueOf(maxLoadPercent))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            /*
             * Потолок оборудования: daily_limit выполнений в день ×
             * норму на одно изделие. Ноль и отрицательное значение
             * означают «ограничение не задано».
             */
            int dailyLimit = node.getDailyLimit() == null
                    ? 0
                    : node.getDailyLimit();

            BigDecimal equipmentLimit = dailyLimit > 0
                    ? labor.multiply(BigDecimal.valueOf(dailyLimit))
                    : BigDecimal.ZERO;

            /*
             * Рабочий потолок дня: люди, но не больше оборудования.
             */
            BigDecimal dayLimit;

            if (dailyCapacity.signum() <= 0) {
                dayLimit = equipmentLimit.signum() > 0
                        ? equipmentLimit
                        : BigDecimal.ZERO;
            } else if (equipmentLimit.signum() > 0
                    && equipmentLimit.compareTo(dailyCapacity) < 0) {
                dayLimit = equipmentLimit;
            } else {
                dayLimit = dailyCapacity;
            }

            /*
             * Начало: после предшественников (с паузой переходов)
             * и не раньше даты старта.
             */
            LocalDate earliest = startDate;

            for (TechProcessOperation predecessor : node.getPredecessors()) {
                LocalDate predFinish = predecessor.getId() == null
                        ? null
                        : finishByNode.get(predecessor.getId());

                if (predFinish != null) {
                    LocalDate next = plusWorkingDays(
                            predFinish, transitionDays + 1, calendar);
                    if (next.isAfter(earliest)) {
                        earliest = next;
                    }
                }
            }

            /*
             * Нулевая мощность: срок определить нельзя. Операция
             * не получает дату, а сводка помечает расчёт
             * недостоверным — иначе «весь объём за один день»
             * выдал бы фиктивный срок.
             */
            if (unitId == null || employees == 0) {
                missingCapacity++;

                if (unitId == null) {
                    warnings.add("«" + operation.getOperationName().getName()
                            + "»: операция не привязана к цеху — "
                            + "срок по ней определить нельзя.");
                } else {
                    warnings.add("«" + operation.getOperationName().getName()
                            + "»: в цехе «" + unitName
                            + "» нет сотрудников — срок по нему "
                            + "определить нельзя.");
                }

                rows.add(new ForecastOperationRow(
                        node.getPosition() != null ? node.getPosition() : 0,
                        operation.getOperationName().getCode(),
                        operation.getOperationName().getName(),
                        operation.getOperationType().getName(),
                        unitName,
                        serviceCode,
                        labor,
                        machine,
                        dailyLimit,
                        hoursForQty,
                        employees,
                        dailyCapacity,
                        dayLimit,
                        null,
                        null,
                        0.0,
                        false,
                        unitId == null
                                ? "Операция не привязана к цеху"
                                : "В цехе нет сотрудников"
                ));

                continue;
            }

            Placement placement = placeOperation(
                    unitId,
                    hoursForQty,
                    dayLimit,
                    earliest,
                    occupied,
                    calendar
            );

            finishByNode.put(node.getId(), placement.finish);

            if (overallFinish == null
                    || placement.finish.isAfter(overallFinish)) {
                overallFinish = placement.finish;
            }

            totalLabor = totalLabor.add(hoursForQty);

            rows.add(new ForecastOperationRow(
                    node.getPosition() != null ? node.getPosition() : 0,
                    operation.getOperationName().getCode(),
                    operation.getOperationName().getName(),
                    operation.getOperationType().getName(),
                    unitName,
                    serviceCode,
                    labor,
                    machine,
                    dailyLimit,
                    hoursForQty,
                    employees,
                    dailyCapacity,
                    dayLimit,
                    placement.start,
                    placement.finish,
                    placement.loadPercent,
                    false,
                    null
            ));
        }

        boolean reliable = missingCapacity == 0;

        /*
         * Если все операции без мощности, даты завершения нет вовсе.
         * Если мощности есть хотя бы у одной — срок считается по ней,
         * но недостоверность всё равно видна в сводке.
         */
        LocalDate finishDate = reliable
                ? (overallFinish != null ? overallFinish : startDate)
                : null;

        int workingDays = finishDate == null
                ? 0
                : Math.max(1, workingDaysBetween(startDate, finishDate,
                        calendar));

        /*
         * Лимитирующая стадия — операция, которая закончилась
         * последней. Именно она держит общий срок.
         */
        String limitingOperation = "—";
        BigDecimal limitingHours = BigDecimal.ZERO;

        if (finishDate != null) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                ForecastOperationRow row = rows.get(i);

                if (row.finish() != null
                        && row.finish().equals(overallFinish)) {
                    limitingOperation = row.operationName();
                    limitingHours = row.hoursForQty();

                    rows.set(i, new ForecastOperationRow(
                            row.position(), row.operationCode(),
                            row.operationName(), row.operationTypeName(),
                            row.unitName(), row.serviceCode(),
                            row.laborHours(), row.machineHours(),
                            row.dailyLimit(), row.hoursForQty(),
                            row.capacityEmployees(),
                            row.dailyCapacityHours(),
                            row.effectiveDailyLimitHours(),
                            row.start(), row.finish(),
                            row.loadPercent(), true, row.bottleneckHint()
                    ));

                    break;
                }
            }
        }

        boolean feasible = reliable
                && finishDate != null
                && !finishDate.isAfter(
                        startDate.plusDays(MAX_DAYS_AHEAD));

        if (reliable && finishDate != null && !feasible) {
            warnings.add("Расчётный срок выходит за "
                    + (MAX_DAYS_AHEAD / 365)
                    + " года — не хватает мощностей.");
        }

        /*
         * Отсечка изготовления приоритета: сколько рабочих дней
         * остаётся на изготовление перед сроком отгрузки. Если
         * расчёт дольше — заказ не укладывается в срок.
         */
        if (reliable && finishDate != null && priority != null
                && priority.getMaxManufacturingDays() != null
                && priority.getMaxManufacturingDays() > 0
                && workingDays > priority.getMaxManufacturingDays()) {

            feasible = false;

            warnings.add("Приоритет «" + priority.getName()
                    + "» отводит на изготовление не более "
                    + priority.getMaxManufacturingDays()
                    + " раб. дн., а расчёт даёт " + workingDays
                    + " раб. дн. — заказ не укладывается в срок отгрузки.");
        }

        ForecastSummary summary = new ForecastSummary(
                classifier.getId(),
                classifier.getCode(),
                classifier.getDisplayName(),
                classifier.getProductType() != null
                        ? classifier.getProductType().getDisplayName()
                        : "—",
                quantity,
                startDate,
                finishDate,
                totalLabor,
                workingDays,
                feasible,
                limitingOperation,
                limitingHours,
                priority != null ? priority.getCode() : null,
                priority != null ? priority.getName() : "—",
                priority != null ? priority.getBadgeClass() : null,
                startDays,
                transitionDays,
                maxLoadPercent,
                missingCapacity
        );

        List<ForecastWeekRow> weeklyPlan = finishDate == null
                ? List.of()
                : buildWeeklyPlan(rows, startDate, finishDate, calendar);

        return new CapacityForecastResult(
                summary, rows, weeklyPlan, warnings, feasible);
    }

    /*
     * Приоритет расчёта: выбранный диспетчером, иначе «обычный»,
     * иначе — пусто (расчёт без правил приоритета).
     */
    private OrderPriority resolvePriority(
            Long priorityId,
            List<String> warnings
    ) {
        if (priorityId != null) {
            OrderPriority chosen = orderPriorityRepository
                    .findById(priorityId)
                    .orElse(null);

            if (chosen != null) {
                return chosen;
            }

            warnings.add("Выбранный приоритет не найден — "
                    + "расчёт выполнен по обычному приоритету.");
        }

        OrderPriority fallback = orderPriorityRepository
                .findByCode(DEFAULT_PRIORITY_CODE)
                .orElse(null);

        if (fallback == null) {
            warnings.add("В справочнике нет приоритета «"
                    + DEFAULT_PRIORITY_CODE + "» — "
                    + "расчёт без правил запуска и загрузки.");
        }

        return fallback;
    }

    /*
     * Техпроцесс изделия: базовый маршрут классификатора, а при его
     * отсутствии — первый действующий применённый маршрут.
     */
    private TechProcess resolveProcess(
            ProductClassifier classifier,
            List<String> warnings
    ) {
        TechProcessClassifier basic =
                techProcessClassifierRepository
                        .findBasicForClassifier(classifier.getId())
                        .orElse(null);

        TechProcess process = basic != null
                ? basic.getTechProcess()
                : null;

        if (process == null) {
            process = techProcessClassifierRepository
                    .findByClassifier_Id(classifier.getId())
                    .stream()
                    .map(TechProcessClassifier::getTechProcess)
                    .filter(candidate -> candidate != null
                            && candidate.isActive())
                    .findFirst()
                    .orElse(null);

            if (process != null) {
                warnings.add("У изделия нет базового техпроцесса — "
                        + "взят первый действующий применённый маршрут «"
                        + process.getCode() + "».");
            }
        }

        if (process == null) {
            return null;
        }

        /*
         * findFullById грузит операции и связи с рабочими местами;
         * DAG-предшественники догружаются лениво внутри транзакции.
         */
        return techProcessRepository.findFullById(process.getId())
                .orElse(process);
    }

    /*
     * Топологический порядок операций: предшественники раньше
     * потомков. Тот же алгоритм, что в планировщике заказов.
     */
    private List<TechProcessOperation> orderedRoute(TechProcess process) {
        List<TechProcessOperation> nodes =
                new ArrayList<>(process.getOperations());

        if (nodes.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> indegree = new LinkedHashMap<>();
        Map<Long, TechProcessOperation> byId = new HashMap<>();
        Map<Long, List<TechProcessOperation>> successors = new HashMap<>();

        for (TechProcessOperation node : nodes) {
            byId.put(node.getId(), node);
            indegree.putIfAbsent(node.getId(), 0);

            for (TechProcessOperation predecessor : node.getPredecessors()) {
                if (predecessor.getId() == null) {
                    continue;
                }

                successors
                        .computeIfAbsent(
                                predecessor.getId(),
                                key -> new ArrayList<>()
                        )
                        .add(node);

                indegree.merge(node.getId(), 1, Integer::sum);
            }
        }

        Deque<TechProcessOperation> ready = new ArrayDeque<>();

        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(byId.get(id));
            }
        });

        List<TechProcessOperation> ordered = new ArrayList<>();

        while (!ready.isEmpty()) {
            TechProcessOperation node = ready.poll();
            ordered.add(node);

            for (TechProcessOperation next
                    : successors.getOrDefault(node.getId(), List.of())) {

                int left = indegree.merge(next.getId(), -1, Integer::sum);

                if (left == 0) {
                    ready.add(next);
                }
            }
        }

        return ordered;
    }

    /*
     * Размещение часов операции по рабочим дням.
     *
     * Идём от earliest вперёд: в каждом дне свободно
     * dayLimit минус уже занятое (внесённый план + ранее
     * размещённые операции этого расчёта). Занимаем минимум из
     * остатка и свободного. День, в который операция закончилась,
     * становится датой окончания.
     */
    private Placement placeOperation(
            Long unitId,
            BigDecimal hours,
            BigDecimal dayLimit,
            LocalDate earliest,
            Map<Long, Map<LocalDate, BigDecimal>> occupied,
            WorkingCalendar calendar
    ) {
        LocalDate start = calendar.nextWorkingDay(earliest);

        if (hours.signum() <= 0) {
            return new Placement(start, start, 0.0);
        }

        if (dayLimit.signum() <= 0) {
            return new Placement(start, start, 100.0);
        }

        Map<LocalDate, BigDecimal> unitLoad = occupied
                .computeIfAbsent(unitId, key -> new HashMap<>());

        BigDecimal remaining = hours;
        LocalDate date = start;
        LocalDate finish = start;
        double maxLoad = 0.0;
        int guard = 0;

        while (remaining.signum() > 0 && guard++ < MAX_DAYS_AHEAD) {
            BigDecimal busy = unitLoad.getOrDefault(
                    date, BigDecimal.ZERO);
            BigDecimal free = dayLimit.subtract(busy);

            if (free.signum() <= 0) {
                date = calendar.nextWorkingDay(date.plusDays(1));
                continue;
            }

            BigDecimal placed = remaining.min(free);

            unitLoad.merge(date, placed, BigDecimal::add);
            remaining = remaining.subtract(placed);
            finish = date;

            double loadPct = busy.add(placed)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(dayLimit, 1, RoundingMode.HALF_UP)
                    .doubleValue();

            if (loadPct > maxLoad) {
                maxLoad = loadPct;
            }

            if (remaining.signum() > 0) {
                date = calendar.nextWorkingDay(date.plusDays(1));
            }
        }

        return new Placement(start, finish, maxLoad);
    }

    /*
     * Загрузка из внесённого плана: CONFIRMED строки графика
     * по подразделениям и дням. Черновики (DRAFT) не учитываются —
     * они ещё не заняли людей.
     */
    private Map<Long, Map<LocalDate, BigDecimal>> loadConfirmed(
            LocalDate from,
            LocalDate to
    ) {
        Map<Long, Map<LocalDate, BigDecimal>> result = new HashMap<>();

        List<OrderScheduleRowRepository.CapacityRowView> rows =
                scheduleRowRepository.findRowsForCapacityAnalysis(
                        from,
                        to,
                        List.of(ScheduleRowStatus.CONFIRMED)
                );

        for (OrderScheduleRowRepository.CapacityRowView row : rows) {
            if (row.getUnitId() == null) {
                continue;
            }

            BigDecimal hours = row.getLaborHours() == null
                    ? BigDecimal.ZERO
                    : row.getLaborHours();

            result
                    .computeIfAbsent(
                            row.getUnitId(),
                            key -> new HashMap<>()
                    )
                    .merge(row.getPlannedDate(), hours, BigDecimal::add);
        }

        return result;
    }

    /*
     * Сотрудники по подразделениям: мощность считается как
     * люди × 8 часов — формула планировщика.
     */
    private Map<Long, Integer> countEmployeesByUnit() {
        Map<Long, Integer> result = new HashMap<>();

        List<AppUser> employees = userRepository
                .findByActiveTrueOrderByFullNameAscUsernameAsc();

        for (AppUser employee : employees) {
            if (employee.getOrganizationUnit() == null) {
                continue;
            }

            result.merge(
                    employee.getOrganizationUnit().getId(),
                    1,
                    Integer::sum
            );
        }

        return result;
    }

    /*
     * Рабочий календарь: выходные и праздники справочника
     * holiday_days. Праздники читаются одним запросом на весь
     * горизонт расчёта.
     */
    private WorkingCalendar loadCalendar(LocalDate from, LocalDate to) {
        Set<LocalDate> holidays = new HashSet<>();

        for (HolidayDay holiday : holidayDayRepository
                .findByHolidayDateBetweenOrderByHolidayDateAsc(from, to)) {
            holidays.add(holiday.getHolidayDate());
        }

        return new WorkingCalendar(holidays);
    }

    /*
     * Сдвиг на N рабочих дней. Ноль — ближайший рабочий день,
     * начиная с самой даты.
     */
    private static LocalDate plusWorkingDays(
            LocalDate date,
            int days,
            WorkingCalendar calendar
    ) {
        LocalDate result = calendar.nextWorkingDay(date);

        for (int i = 0; i < days; i++) {
            result = calendar.nextWorkingDay(result.plusDays(1));
        }

        return result;
    }

    /*
     * Рабочих дней между датами включительно. Минимум 1 — «в тот же
     * день» это один рабочий день, а не ноль.
     */
    private static int workingDaysBetween(
            LocalDate from,
            LocalDate to,
            WorkingCalendar calendar
    ) {
        int count = 0;

        for (LocalDate date = from; !date.isAfter(to);
             date = date.plusDays(1)) {
            if (calendar.isWorkingDay(date)) {
                count++;
            }
        }

        return Math.max(count, 1);
    }

    /*
     * Недельный план: сколько часов заказа попадает на каждую
     * неделю. Часы операции распределяются по её рабочим дням
     * равными долями.
     */
    private List<ForecastWeekRow> buildWeeklyPlan(
            List<ForecastOperationRow> rows,
            LocalDate startDate,
            LocalDate finishDate,
            WorkingCalendar calendar
    ) {
        Map<LocalDate, BigDecimal> hoursByDate = new HashMap<>();

        for (ForecastOperationRow row : rows) {
            if (row.start() == null || row.finish() == null) {
                continue;
            }

            int days = 0;

            for (LocalDate date = row.start();
                 !date.isAfter(row.finish());
                 date = date.plusDays(1)) {
                if (calendar.isWorkingDay(date)) {
                    days++;
                }
            }

            days = Math.max(days, 1);

            BigDecimal perDay = row.hoursForQty()
                    .divide(BigDecimal.valueOf(days),
                            4, RoundingMode.HALF_UP);

            for (LocalDate date = row.start();
                 !date.isAfter(row.finish());
                 date = date.plusDays(1)) {
                if (calendar.isWorkingDay(date)) {
                    hoursByDate.merge(date, perDay, BigDecimal::add);
                }
            }
        }

        long weeksBetween = (workingDaysBetween(startDate, finishDate,
                calendar) + 4) / 5;
        int weeks = (int) Math.min(
                Math.max(1, weeksBetween),
                MAX_PLAN_WEEKS
        );

        WeekFields weekFields = WeekFields.of(DayOfWeek.MONDAY, 1);

        LocalDate firstMonday = startDate.with(
                weekFields.dayOfWeek(), 1);
        if (firstMonday.isBefore(startDate)) {
            firstMonday = startDate;
        }

        List<ForecastWeekRow> result = new ArrayList<>();

        for (int wi = 0; wi < weeks; wi++) {
            LocalDate weekFrom = firstMonday.plusWeeks(wi);
            LocalDate weekTo = weekFrom.plusDays(4);

            BigDecimal labor = BigDecimal.ZERO;

            for (LocalDate date = weekFrom;
                 !date.isAfter(weekTo);
                 date = date.plusDays(1)) {

                labor = labor.add(
                        hoursByDate.getOrDefault(date, BigDecimal.ZERO));
            }

            double loadPercent = labor.signum() > 0
                    ? labor.multiply(BigDecimal.valueOf(100))
                        .divide(WORKDAY_HOURS, 1, RoundingMode.HALF_UP)
                        .doubleValue()
                    : 0.0;

            result.add(new ForecastWeekRow(
                    weekFrom.get(weekFields.weekOfWeekBasedYear()),
                    weekFrom,
                    weekTo,
                    labor.setScale(1, RoundingMode.HALF_UP),
                    BigDecimal.ZERO,
                    Math.min(loadPercent, 100.0),
                    Math.min(100.0, labor.doubleValue() * 4)
            ));
        }

        return result;
    }

    private CapacityForecastResult emptyResult(
            int quantity,
            LocalDate startDate,
            List<String> warnings,
            String message
    ) {
        warnings.add(message);

        /*
         * finishDate = null: расчёт не состоялся, и подставлять дату
         * старта вместо срока нельзя — диспетчер прочитал бы её как
         * «заказ готов в день начала».
         */
        return new CapacityForecastResult(
                new ForecastSummary(
                        null, null, "—", "—",
                        quantity, startDate, null,
                        BigDecimal.ZERO, 0, false,
                        "—", BigDecimal.ZERO,
                        null, null, null, 0, 0, 100, 0
                ),
                List.of(),
                List.of(),
                warnings,
                false
        );
    }

    /** Результат размещения одной операции. */
    private record Placement(
            LocalDate start,
            LocalDate finish,
            double loadPercent
    ) {}

    /**
     * Рабочий календарь: будни без праздников справочника.
     */
    private static final class WorkingCalendar {

        private final Set<LocalDate> holidays;

        private WorkingCalendar(Set<LocalDate> holidays) {
            this.holidays = holidays;
        }

        private boolean isWorkingDay(LocalDate date) {
            DayOfWeek day = date.getDayOfWeek();

            return day != DayOfWeek.SATURDAY
                    && day != DayOfWeek.SUNDAY
                    && !holidays.contains(date);
        }

        private LocalDate nextWorkingDay(LocalDate date) {
            LocalDate candidate = date;

            int guard = 0;

            while (!isWorkingDay(candidate) && guard++ < 30) {
                candidate = candidate.plusDays(1);
            }

            return candidate;
        }
    }
}

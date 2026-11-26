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
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessClassifier;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Плановый расчёт выполнения — калькулятор возможностей цехов.
 *
 * Диспетчер выбирает изделие, количество и дату начала, а сервис
 * прогоняет маршрут операций по мощностям: для каждой операции берёт
 * цех-хозяина, считает мощность (сотрудники × 8 ч) и свободное время
 * с учётом уже внесённого плана (CONFIRMED строки графика), затем
 * размещает часы по рабочим дням. Результат — дата завершения и
 * лимитирующая стадия.
 *
 * Расчёт намеренно повторяет формулу мощности планировщика
 * ({@link OrderSchedulingService}): люди × 8 часов, чтобы прогноз
 * диспетчера и даты плана заказа не расходились.
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

    private final TechProcessRepository techProcessRepository;
    private final TechProcessClassifierRepository
            techProcessClassifierRepository;
    private final ProductClassifierRepository
            productClassifierRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final UserRepository userRepository;

    /**
     * Расчёт возможности изготовления.
     *
     * @param form       изделие, количество, дата начала
     * @param ignoreLoad не учитывать уже внесённый план (CONFIRMED)
     */
    @Transactional(readOnly = true)
    public CapacityForecastResult calculate(
            CapacityForecastForm form,
            boolean ignoreLoad
    ) {
        int quantity = Math.max(1, form.quantity());
        LocalDate startDate = form.startDate() != null
                ? form.startDate()
                : LocalDate.now();

        List<String> warnings = new ArrayList<>();

        ProductClassifier classifier = form.classifierId() == null
                ? null
                : productClassifierRepository
                    .findById(form.classifierId())
                    .orElse(null);

        if (classifier == null) {
            return emptyResult(quantity, startDate, warnings,
                    "Изделие не найдено — выберите его из справочника.");
        }

        TechProcess process = resolveProcess(classifier, warnings);

        if (process == null) {
            return emptyResult(quantity, startDate, warnings,
                    "Для изделия «" + classifier.getDisplayName()
                            + "» не задан техпроцесс. "
                            + "Сначала создайте маршрут операций.");
        }

        List<TechProcessOperation> route = orderedRoute(process);

        if (route.isEmpty()) {
            return emptyResult(quantity, startDate, warnings,
                    "Техпроцесс " + process.getCode()
                            + " не содержит операций.");
        }

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
        LocalDate overallFinish = startDate;

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

            BigDecimal dailyCapacity = WORKDAY_HOURS
                    .multiply(BigDecimal.valueOf(employees));

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
             * Начало: после предшественников и не раньше даты старта.
             */
            LocalDate earliest = startDate;

            for (TechProcessOperation predecessor : node.getPredecessors()) {
                LocalDate predFinish = predecessor.getId() == null
                        ? null
                        : finishByNode.get(predecessor.getId());

                if (predFinish != null) {
                    LocalDate next = nextWorkingDay(predFinish);
                    if (next.isAfter(earliest)) {
                        earliest = next;
                    }
                }
            }

            if (unitId == null) {
                warnings.add("«" + operation.getOperationName().getName()
                        + "»: операция не привязана к цеху — "
                        + "срок рассчитан без ограничения мощности.");
            } else if (employees == 0) {
                warnings.add("«" + operation.getOperationName().getName()
                        + "»: в цехе «" + unitName
                        + "» нет сотрудников — мощность нулевая, "
                        + "срок может быть занижен.");
            }

            Placement placement = placeOperation(
                    unitId,
                    hoursForQty,
                    dayLimit,
                    earliest,
                    occupied
            );

            finishByNode.put(node.getId(), placement.finish);

            if (placement.finish.isAfter(overallFinish)) {
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
                    unitId == null
                            ? "Операция не привязана к цеху"
                            : (employees == 0
                                    ? "В цехе нет сотрудников"
                                    : null)
            ));
        }

        /*
         * Лимитирующая стадия — операция, которая закончилась
         * последней. Именно она держит общий срок.
         */
        String limitingOperation = "—";
        BigDecimal limitingHours = BigDecimal.ZERO;

        for (int i = rows.size() - 1; i >= 0; i--) {
            ForecastOperationRow row = rows.get(i);

            if (row.finish().equals(overallFinish)) {
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

        boolean feasible = !overallFinish.isAfter(
                startDate.plusDays(MAX_DAYS_AHEAD));

        if (!feasible) {
            warnings.add("Расчётный срок выходит за "
                    + (MAX_DAYS_AHEAD / 365)
                    + " года — не хватает мощностей "
                    + "или маршрут содержит операцию без цеха.");
        }

        int totalDays = (int) Math.max(
                0,
                ChronoUnit.DAYS.between(startDate, overallFinish));

        ForecastSummary summary = new ForecastSummary(
                classifier.getId(),
                classifier.getCode(),
                classifier.getDisplayName(),
                classifier.getProductType() != null
                        ? classifier.getProductType().getDisplayName()
                        : "—",
                quantity,
                startDate,
                overallFinish,
                totalLabor,
                totalDays,
                feasible,
                limitingOperation,
                limitingHours
        );

        List<ForecastWeekRow> weeklyPlan =
                buildWeeklyPlan(rows, startDate, overallFinish);

        return new CapacityForecastResult(
                summary, rows, weeklyPlan, warnings, feasible);
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
            warnings.add("У изделия нет базового техпроцесса — "
                    + "взят первый действующий применённый маршрут.");
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
     *
     * Операция без цеха или с нулевой мощностью получает весь
     * объём в первый рабочий день: расчёт не должен «зависать»,
     * а предупреждение диспетчер уже увидел.
     */
    private Placement placeOperation(
            Long unitId,
            BigDecimal hours,
            BigDecimal dayLimit,
            LocalDate earliest,
            Map<Long, Map<LocalDate, BigDecimal>> occupied
    ) {
        /*
         * Операция не может начаться раньше earliest. Если earliest
         * попадает на выходной, сдвигаем вперёд до рабочего дня;
         * вычитать день нельзя — рабочий earliest тогда терялся,
         * и операция начиналась на день раньше даты старта.
         */
        LocalDate start = nextWorkingDay(earliest);

        if (hours.signum() <= 0) {
            return new Placement(start, start, 0.0);
        }

        if (unitId == null || dayLimit.signum() <= 0) {
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
                date = nextWorkingDay(date);
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
                date = nextWorkingDay(date);
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
     * Недельный план: сколько часов заказа попадает на каждую
     * неделю. Часы операции распределяются по её рабочим дням
     * равными долями.
     */
    private List<ForecastWeekRow> buildWeeklyPlan(
            List<ForecastOperationRow> rows,
            LocalDate startDate,
            LocalDate finishDate
    ) {
        Map<LocalDate, BigDecimal> hoursByDate = new HashMap<>();

        for (ForecastOperationRow row : rows) {
            long days = Math.max(
                    1,
                    ChronoUnit.DAYS.between(row.start(), row.finish()) + 1
            );

            BigDecimal perDay = row.hoursForQty()
                    .divide(BigDecimal.valueOf(days),
                            4, RoundingMode.HALF_UP);

            LocalDate date = row.start();

            while (!date.isAfter(row.finish())) {
                if (isWorkingDay(date)) {
                    hoursByDate.merge(date, perDay, BigDecimal::add);
                }

                date = date.plusDays(1);
            }
        }

        long weeksBetween = ChronoUnit.WEEKS.between(
                startDate, finishDate) + 1;
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

    private static boolean isWorkingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private static LocalDate nextWorkingDay(LocalDate date) {
        LocalDate candidate = date;

        if (candidate == null) {
            return LocalDate.now();
        }

        int guard = 0;

        while (!isWorkingDay(candidate) && guard++ < 10) {
            candidate = candidate.plusDays(1);
        }

        return candidate;
    }

    private CapacityForecastResult emptyResult(
            int quantity,
            LocalDate startDate,
            List<String> warnings,
            String message
    ) {
        warnings.add(message);

        return new CapacityForecastResult(
                new ForecastSummary(
                        null, null, "—", "—",
                        quantity, startDate, startDate,
                        BigDecimal.ZERO, 0, false,
                        "—", BigDecimal.ZERO
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
}
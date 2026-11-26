package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CapacityDayRow;
import ru.company.production.dto.CapacityKpi;
import ru.company.production.dto.CapacityUnitSummary;
import ru.company.production.dto.CapacityWeekRow;
import ru.company.production.dto.Recommendation;
import ru.company.production.dto.UnassignedOperation;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.EmployeeAbsence;
import ru.company.production.entity.HolidayDay;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.HolidayDayRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Анализ производственных мощностей.
 *
 * Загрузка считается по строкам графика заказов (order_schedule_rows):
 * CONFIRMED — внесённый план, DRAFT — предварительные расчёты. В
 * занятость идёт только план; черновики показываются отдельным
 * сегментом полосы, чтобы диспетчер видел потенциальную загрузку,
 * но не считал её подтверждённой.
 *
 * Мощность подразделения — это присутствующие сотрудники × 8 часов.
 * Сотрудники берутся из справочника пользователей по подразделению,
 * отсутствия вычитаются по производственному календарю, праздники
 * обнуляют день. Формула совпадает с планировщиком
 * ({@link OrderSchedulingService}), чтобы цифры анализа и даты плана
 * не расходились.
 *
 * Раздел «Не назначено» показывает операции двух видов:
 * без привязки к подразделению и без закреплённых исполнителей.
 * Это те операции, из-за которых мощность либо нулевая, либо
 * «висит в воздухе» и не видна мастеру.
 */
@Service
@RequiredArgsConstructor
public class CapacityAnalysisService {

    /** Рабочий день в часах — та же константа, что в планировщике. */
    private static final BigDecimal WORKDAY_HOURS = new BigDecimal("8");

    /** Порог предупреждения: 85 % — зона риска. */
    public static final double WARNING_THRESHOLD = 85.0;

    /** Порог перегруза: свыше 100 % — дефицит мощности. */
    public static final double CRITICAL_THRESHOLD = 100.0;

    private static final int MIN_WEEKS = 1;
    private static final int MAX_WEEKS = 16;
    private static final int DEFAULT_WEEKS = 8;

    private final OrderScheduleRowRepository scheduleRowRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final WorkshopStaffBindingRepository bindingRepository;
    private final EmployeeAbsenceRepository absenceRepository;
    private final HolidayDayRepository holidayRepository;
    private final OperationRepository operationRepository;
    private final UserRepository userRepository;

    /**
     * Полный анализ мощностей за N недель.
     */
    @Transactional(readOnly = true)
    public CapacityAnalysisResult analyze(Integer weeks) {
        int w = weeks == null
                ? DEFAULT_WEEKS
                : Math.max(MIN_WEEKS, Math.min(weeks, MAX_WEEKS));

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusWeeks(w).minusDays(1);

        /*
         * 1. Строки графика: разделяем план и черновики.
         * Карта «подразделение → дата → часы».
         */
        Map<Long, Map<LocalDate, BigDecimal>> confirmedLoad = new HashMap<>();
        Map<Long, Map<LocalDate, BigDecimal>> draftLoad = new HashMap<>();

        /*
         * Строки без подразделения: копим по операции, чтобы в отчёте
         * «не назначено» показать реальные часы, а не нули.
         */
        Map<Long, Integer> hoursWithoutUnit = new LinkedHashMap<>();
        Map<Long, String> namesWithoutUnit = new HashMap<>();

        List<OrderScheduleRowRepository.CapacityRowView> rows =
                scheduleRowRepository.findRowsForCapacityAnalysis(
                        from,
                        to,
                        List.of(
                                ScheduleRowStatus.CONFIRMED,
                                ScheduleRowStatus.DRAFT
                        )
                );

        for (OrderScheduleRowRepository.CapacityRowView row : rows) {
            LocalDate date = row.getPlannedDate();
            BigDecimal hours = row.getLaborHours() == null
                    ? BigDecimal.ZERO
                    : row.getLaborHours();
            Long unitId = row.getUnitId();
            Long operationId = row.getOperationId();
            String operationName = row.getOperationName();

            boolean confirmed = row.getStatus() == ScheduleRowStatus.CONFIRMED;

            if (unitId == null) {
                int h = hours
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();
                hoursWithoutUnit.merge(operationId, h, Integer::sum);
                if (operationName != null) {
                    namesWithoutUnit.put(operationId, operationName);
                }
                continue;
            }

            if (confirmed) {
                confirmedLoad
                        .computeIfAbsent(unitId, key -> new HashMap<>())
                        .merge(date, hours, BigDecimal::add);
            } else {
                draftLoad
                        .computeIfAbsent(unitId, key -> new HashMap<>())
                        .merge(date, hours, BigDecimal::add);
            }
        }

        /*
         * 2. Подразделения и их сотрудники.
         */
        List<OrganizationUnit> allUnits = organizationUnitRepository
                .findAllByActiveTrueOrderByNameAsc();

        Map<Long, List<Long>> employeesByUnit = countEmployeesByUnit();

        /*
         * 3. Календарь: праздники и отсутствия одним снимком.
         */
        Set<LocalDate> holidays = loadHolidays(from, to);
        Map<LocalDate, Set<Long>> absences = loadAbsences(from, to);

        /*
         * 4. Сводка по каждому подразделению.
         */
        List<CapacityUnitSummary> unitSummaries = new ArrayList<>();

        BigDecimal totalCapacity = BigDecimal.ZERO;
        BigDecimal totalLoad = BigDecimal.ZERO;
        int totalCriticalWeeks = 0;
        int totalWarningWeeks = 0;
        int totalDeficitDays = 0;

        for (OrganizationUnit unit : allUnits) {
            CapacityUnitSummary summary = buildUnitSummary(
                    unit,
                    employeesByUnit.getOrDefault(unit.getId(), List.of()),
                    from,
                    w,
                    confirmedLoad.getOrDefault(unit.getId(), Map.of()),
                    draftLoad.getOrDefault(unit.getId(), Map.of()),
                    holidays,
                    absences
            );

            unitSummaries.add(summary);

            totalCapacity = totalCapacity.add(summary.totalCapacityHours());
            totalLoad = totalLoad.add(summary.totalLoadHours());
            totalCriticalWeeks += summary.criticalCount();
            totalWarningWeeks += summary.warningCount();
            totalDeficitDays += summary.deficitDays();
        }

        /*
         * 5. KPI.
         */
        double totalLoadPct = totalCapacity.signum() > 0
                ? totalLoad.multiply(BigDecimal.valueOf(100))
                    .divide(totalCapacity, 2, RoundingMode.HALF_UP)
                    .doubleValue()
                : 0;

        double freeHours = Math.max(0,
                totalCapacity.subtract(totalLoad).doubleValue());

        /*
         * 6. Не назначенные операции.
         */
        List<UnassignedOperation> unassigned =
                findUnassignedOperations(hoursWithoutUnit, namesWithoutUnit);

        CapacityKpi kpi = new CapacityKpi(
                totalCapacity,
                totalLoad,
                totalLoadPct,
                freeHours,
                totalCriticalWeeks,
                totalWarningWeeks,
                unassigned.size(),
                totalDeficitDays
        );

        /*
         * 7. Рекомендации диспетчеру.
         */
        List<Recommendation> recommendations =
                buildRecommendations(unitSummaries, unassigned);

        return new CapacityAnalysisResult(
                kpi,
                unitSummaries,
                unassigned,
                recommendations,
                w,
                from,
                to
        );
    }

    /**
     * Сводка по одному подразделению.
     *
     * Неделя — календарная с понедельника. Дни выходных и праздников
     * остаются в ряду (чтобы таблица не «прыгала»), но мощность и
     * загрузка у них нулевые и в расчёт не идут.
     */
    private CapacityUnitSummary buildUnitSummary(
            OrganizationUnit unit,
            List<Long> employeeIds,
            LocalDate from,
            int weeks,
            Map<LocalDate, BigDecimal> confirmed,
            Map<LocalDate, BigDecimal> draft,
            Set<LocalDate> holidays,
            Map<LocalDate, Set<Long>> absences
    ) {
        List<CapacityWeekRow> weekRows = new ArrayList<>();

        BigDecimal totalCap = BigDecimal.ZERO;
        BigDecimal totalConfirmed = BigDecimal.ZERO;
        BigDecimal totalDraft = BigDecimal.ZERO;
        int deficitDays = 0;
        int criticalCount = 0;
        int warningCount = 0;

        WeekFields weekFields = WeekFields.of(DayOfWeek.MONDAY, 1);

        LocalDate firstMonday = from.with(weekFields.dayOfWeek(), 1);
        if (firstMonday.isBefore(from)) {
            firstMonday = from;
        }

        for (int wi = 0; wi < weeks; wi++) {
            LocalDate weekFrom = firstMonday.plusWeeks(wi);
            LocalDate weekTo = weekFrom.plusDays(4);

            BigDecimal weekCap = BigDecimal.ZERO;
            BigDecimal weekConfirmed = BigDecimal.ZERO;
            BigDecimal weekDraft = BigDecimal.ZERO;
            int criticalDays = 0;

            List<CapacityDayRow> dayRows = new ArrayList<>();

            for (int d = 0; d < 5; d++) {
                LocalDate date = weekFrom.plusDays(d);

                boolean holiday = holidays.contains(date)
                        || date.getDayOfWeek() == DayOfWeek.SATURDAY
                        || date.getDayOfWeek() == DayOfWeek.SUNDAY;

                BigDecimal dayCap = holiday
                        ? BigDecimal.ZERO
                        : capacityHours(employeeIds, date, absences);

                BigDecimal dayConfirmed = confirmed.getOrDefault(
                        date, BigDecimal.ZERO);
                BigDecimal dayDraft = draft.getOrDefault(
                        date, BigDecimal.ZERO);
                BigDecimal dayLoad = dayConfirmed.add(dayDraft);

                double dayPct = percent(dayLoad, dayCap);

                if (!holiday && dayPct > CRITICAL_THRESHOLD) {
                    criticalDays++;
                }

                dayRows.add(new CapacityDayRow(
                        date,
                        date.getDayOfWeek().getValue(),
                        dayCap,
                        dayConfirmed,
                        dayDraft,
                        dayPct,
                        holiday
                ));

                weekCap = weekCap.add(dayCap);
                weekConfirmed = weekConfirmed.add(dayConfirmed);
                weekDraft = weekDraft.add(dayDraft);
            }

            BigDecimal weekLoad = weekConfirmed.add(weekDraft);

            double weekPct = percent(weekLoad, weekCap);
            double weekConfirmedPct = percent(weekConfirmed, weekCap);
            double weekFree = Math.max(0,
                    weekCap.subtract(weekLoad).doubleValue());

            weekRows.add(new CapacityWeekRow(
                    weekFrom.get(weekFields.weekOfWeekBasedYear()),
                    weekFrom,
                    weekTo,
                    weekCap,
                    weekConfirmed,
                    weekDraft,
                    weekLoad,
                    weekPct,
                    weekConfirmedPct,
                    weekFree,
                    criticalDays,
                    dayRows
            ));

            if (weekPct > CRITICAL_THRESHOLD) {
                criticalCount++;
            } else if (weekPct >= WARNING_THRESHOLD) {
                warningCount++;
            }

            deficitDays += criticalDays;
            totalCap = totalCap.add(weekCap);
            totalConfirmed = totalConfirmed.add(weekConfirmed);
            totalDraft = totalDraft.add(weekDraft);
        }

        BigDecimal totalLoad = totalConfirmed.add(totalDraft);
        double loadPct = percent(totalLoad, totalCap);
        double freeHours = Math.max(0,
                totalCap.subtract(totalLoad).doubleValue());

        return new CapacityUnitSummary(
                unit.getId(),
                unit.getCode(),
                unit.getName(),
                unit.getService() != null
                        ? unit.getService().getCode()
                        : null,
                unit.getService() != null
                        ? unit.getService().getName()
                        : null,
                weeks,
                weekRows,
                totalCap,
                totalConfirmed,
                totalDraft,
                totalLoad,
                loadPct,
                freeHours,
                criticalCount,
                warningCount,
                deficitDays
        );
    }

    /**
     * Мощность подразделения в конкретный день:
     * присутствующие сотрудники × 8 часов.
     */
    private BigDecimal capacityHours(
            List<Long> employeeIds,
            LocalDate date,
            Map<LocalDate, Set<Long>> absences
    ) {
        if (employeeIds.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Set<Long> absent = absences.getOrDefault(date, Set.of());

        int present = 0;

        for (Long employeeId : employeeIds) {
            if (!absent.contains(employeeId)) {
                present++;
            }
        }

        return WORKDAY_HOURS.multiply(BigDecimal.valueOf(present));
    }

    /** Процент загрузки; при нулевой мощности — 0, а не деление на ноль. */
    private static double percent(BigDecimal load, BigDecimal capacity) {
        if (capacity == null || capacity.signum() <= 0) {
            return load != null && load.signum() > 0 ? 100.0 : 0.0;
        }

        return load.multiply(BigDecimal.valueOf(100))
                .divide(capacity, 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Сотрудники по подразделениям: карта «подразделение → id людей».
     * Один запрос вместо обхода каждого цеха.
     */
    private Map<Long, List<Long>> countEmployeesByUnit() {
        Map<Long, List<Long>> result = new HashMap<>();

        List<AppUser> employees = userRepository
                .findByActiveTrueOrderByFullNameAscUsernameAsc();

        for (AppUser employee : employees) {
            if (employee.getOrganizationUnit() == null) {
                continue;
            }

            result.computeIfAbsent(
                            employee.getOrganizationUnit().getId(),
                            key -> new ArrayList<>()
                    )
                    .add(employee.getId());
        }

        return result;
    }

    private Set<LocalDate> loadHolidays(LocalDate from, LocalDate to) {
        Set<LocalDate> result = new HashSet<>();

        for (HolidayDay holiday : holidayRepository
                .findByHolidayDateBetweenOrderByHolidayDateAsc(from, to)) {
            result.add(holiday.getHolidayDate());
        }

        return result;
    }

    private Map<LocalDate, Set<Long>> loadAbsences(
            LocalDate from,
            LocalDate to
    ) {
        Map<LocalDate, Set<Long>> result = new HashMap<>();

        for (EmployeeAbsence absence : absenceRepository
                .findByAbsenceDateBetween(from, to)) {
            result.computeIfAbsent(
                            absence.getAbsenceDate(),
                            key -> new HashSet<>()
                    )
                    .add(absence.getEmployee().getId());
        }

        return result;
    }

    /**
     * Операции без назначения.
     *
     * Две категории:
     * 1) операция не привязана к подразделению — загрузка по ней
     *    «висит в воздухе», мастер её не видит;
     * 2) операция привязана к цеху, но за ней нет ни одного живого
     *    исполнителя — мощность такого этапа нулевая, планировщик
     *    будет сдвигать даты.
     */
    private List<UnassignedOperation> findUnassignedOperations(
            Map<Long, Integer> hoursWithoutUnit,
            Map<Long, String> namesWithoutUnit
    ) {
        List<UnassignedOperation> result = new ArrayList<>();

        List<Operation> allOperations = operationRepository
                .findAllByOrderByOperationName_NameAsc();

        Set<Long> assignedOperationIds = new HashSet<>();

        List<Long> unitIds = allOperations.stream()
                .filter(op -> op.getOrganizationUnit() != null)
                .map(op -> op.getOrganizationUnit().getId())
                .distinct()
                .toList();

        if (!unitIds.isEmpty()) {
            assignedOperationIds.addAll(
                    bindingRepository.findAssignedOperationIds(unitIds));
        }

        for (Operation operation : allOperations) {
            boolean hasUnit = operation.getOrganizationUnit() != null;
            boolean hasExecutors = assignedOperationIds
                    .contains(operation.getId());

            if (hasUnit && hasExecutors) {
                continue;
            }

            int plannedHours = hoursWithoutUnit
                    .getOrDefault(operation.getId(), 0);

            String hint;

            if (!hasUnit && !hasExecutors) {
                hint = "Нет ни подразделения, ни исполнителей — "
                        + "мощность нулевая, работа не видна мастеру";
            } else if (!hasUnit) {
                hint = "Не привязана к подразделению — "
                        + "загрузка «висит в воздухе»";
            } else {
                hint = "Нет закреплённых исполнителей — мощность = 0";
            }

            result.add(new UnassignedOperation(
                    operation.getId(),
                    operation.getOperationName().getName(),
                    operation.getOperationType().getName(),
                    hasUnit
                            ? operation.getOrganizationUnit()
                                .getService().getCode()
                            : null,
                    hasUnit
                            ? operation.getOrganizationUnit()
                                .getService().getName()
                            : null,
                    0,
                    "",
                    plannedHours,
                    0,
                    hint
            ));
        }

        /*
         * Строки плана без подразделения по операциям, которых нет
         * в справочнике операций (удалённая или устаревшая операция):
         * их тоже надо показать, иначе часы просто исчезнут из отчёта.
         */
        Set<Long> knownOperationIds = allOperations.stream()
                .map(Operation::getId)
                .collect(java.util.stream.Collectors.toSet());

        for (Map.Entry<Long, Integer> entry : hoursWithoutUnit.entrySet()) {
            if (knownOperationIds.contains(entry.getKey())) {
                continue;
            }

            result.add(new UnassignedOperation(
                    entry.getKey(),
                    namesWithoutUnit.getOrDefault(
                            entry.getKey(), "Операция #" + entry.getKey()),
                    "—",
                    null,
                    null,
                    0,
                    "",
                    entry.getValue(),
                    0,
                    "Строки плана без подразделения — "
                            + "операция отсутствует в справочнике"
            ));
        }

        return result;
    }

    /**
     * Рекомендации: сначала критичные, затем предупреждения и
     * возможности догрузки.
     */
    private List<Recommendation> buildRecommendations(
            List<CapacityUnitSummary> summaries,
            List<UnassignedOperation> unassigned
    ) {
        List<Recommendation> result = new ArrayList<>();

        for (CapacityUnitSummary summary : summaries) {
            if (summary.totalCapacityHours().signum() <= 0) {
                continue;
            }

            if (summary.loadPercent() > CRITICAL_THRESHOLD) {
                result.add(new Recommendation(
                        Recommendation.Severity.CRITICAL,
                        summary.unitName(),
                        String.format(
                                "Перегрузка %.0f %% — дефицит %d дн. "
                                        + "в горизонте %d нед.",
                                summary.loadPercent(),
                                summary.deficitDays(),
                                summary.maxWeeks()),
                        "Добавить исполнителей или перенести "
                                + "часть заказов"
                ));
            } else if (summary.loadPercent() >= WARNING_THRESHOLD) {
                result.add(new Recommendation(
                        Recommendation.Severity.WARNING,
                        summary.unitName(),
                        String.format(
                                "Высокая загрузка %.0f %% — зона риска, "
                                        + "свободно %.0f ч",
                                summary.loadPercent(),
                                summary.freeHours()),
                        "Проверить возможность усиления "
                                + "или переноса части работ"
                ));
            } else if (summary.loadPercent() < 40) {
                result.add(new Recommendation(
                        Recommendation.Severity.OPPORTUNITY,
                        summary.unitName(),
                        String.format(
                                "Свободно %.0f %% (%.0f ч) — "
                                        + "можно принять заказы",
                                100 - summary.loadPercent(),
                                summary.freeHours()),
                        "Предложить догрузку из очереди заказов"
                ));
            }
        }

        for (UnassignedOperation operation : unassigned) {
            if (operation.hint().contains("исполнител")) {
                result.add(new Recommendation(
                        Recommendation.Severity.CRITICAL,
                        operation.serviceName() != null
                                ? operation.serviceName()
                                : "—",
                        String.format(
                                "Операция «%s» без исполнителя "
                                        + "(в плане %d ч)",
                                operation.operationName(),
                                operation.plannedHours()),
                        "Закрепить сотрудника в разделе "
                                + "«Сотрудники цеха»"
                ));
            } else if (operation.hint().contains("подразделению")) {
                result.add(new Recommendation(
                        Recommendation.Severity.WARNING,
                        "—",
                        String.format(
                                "Операция «%s» не привязана к цеху "
                                        + "(в плане %d ч)",
                                operation.operationName(),
                                operation.plannedHours()),
                        "Назначить подразделение "
                                + "в справочнике операций"
                ));
            }
        }

        result.sort(Comparator.comparingInt(
                recommendation -> recommendation.severity().ordinal()));

        return result;
    }

    /**
     * Корневой результат анализа для контроллера и шаблона.
     */
    public record CapacityAnalysisResult(
            CapacityKpi kpi,
            List<CapacityUnitSummary> units,
            List<UnassignedOperation> unassigned,
            List<Recommendation> recommendations,
            int weeks,
            LocalDate dateFrom,
            LocalDate dateTo
    ) {}
}

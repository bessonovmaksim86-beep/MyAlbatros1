package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ActualPerformanceKpi;
import ru.company.production.dto.ActualPerformanceResult;
import ru.company.production.dto.ExecutorRankingRow;
import ru.company.production.dto.LateOperationRow;
import ru.company.production.dto.ProductionOutputRow;
import ru.company.production.dto.UnitOperationStats;
import ru.company.production.dto.WeeklyTrendRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Фактическая производительность.
 *
 * Источник — закрытые фактом строки маршрута
 * (product_serial_operations с actual_date). Считает:
 * сколько операций закрыто по цехам, сколько уникальных изделий
 * прошло через цех, сколько было просрочено и переделок, тренд
 * «план/факт» по неделям, выпуск готовых ячеек и рейтинг
 * исполнителей.
 *
 * ТРУДОЁМКОСТЬ. Фактических часов в строках маршрута нет: норма
 * хранится в tech_process_operations и привязана к паре
 * «маршрут + операция». Поэтому KPI «нормо-часы» — это сумма
 * средних норм по выполненным операциям, оценка выработки, а не
 * бухгалтерский учёт. В таблицах цехов часы не показываются
 * вовсе, чтобы не выдавать оценку за факт.
 */
@Service
@RequiredArgsConstructor
public class ActualPerformanceService {

    private static final int DEFAULT_WEEKS = 8;
    private static final int MAX_WEEKS = 26;
    private static final int MIN_WEEKS = 2;

    /** Сколько строк просрочки отдавать на страницу. */
    private static final int LATE_LIMIT = 50;

    private final ProductSerialOperationRepository operationRepository;
    private final ProductSerialRepository serialRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final UserRepository userRepository;

    /**
     * Анализ фактической производительности за N недель.
     */
    @Transactional(readOnly = true)
    public ActualPerformanceResult analyze(Integer weeks) {
        int w = weeks == null
                ? DEFAULT_WEEKS
                : Math.max(MIN_WEEKS, Math.min(weeks, MAX_WEEKS));

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusWeeks(w).minusDays(1);

        /*
         * 1. KPI.
         */
        long operations = operationRepository
                .countDoneBetween(from, to);
        long uniqueSerials = operationRepository
                .countUniqueSerialsBetween(from, to);
        long late = operationRepository
                .countLateBetween(from, to);
        long rework = operationRepository
                .countReworkBetween(from, to);

        /*
         * Выработка в нормо-часах: сумма средних норм закрытых
         * операций.
         *
         * JPQL не поддерживает sum(select avg(...)), поэтому
         * считаем в два шага:
         *   1. findDistinctOperationIdsBetween — уникальные operation.id
         *   2. avgLaborHoursByOperationIds — среднее по всем маршрутам
         */
        List<Long> distinctOpIds = operationRepository
                .findDistinctOperationIdsBetween(from, to);

        BigDecimal avgNorm = distinctOpIds.isEmpty()
                ? BigDecimal.ZERO
                : operationRepository
                    .avgLaborHoursByOperationIds(distinctOpIds);

        BigDecimal normHoursTotal = avgNorm
                .multiply(BigDecimal.valueOf(operations));

        long unitsProduced = serialRepository.countCellReadyBetween(
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay()
        );

        ActualPerformanceKpi kpi = new ActualPerformanceKpi(
                operations,
                uniqueSerials,
                normHoursTotal == null ? BigDecimal.ZERO : normHoursTotal,
                late,
                rework,
                unitsProduced
        );

        /*
         * 2. Факт по цехам.
         */
        List<UnitOperationStats> unitStats = loadUnitStats(from, to);

        /*
         * 3. Тренд по неделям.
         */
        List<WeeklyTrendRow> weeklyTrend = loadWeeklyTrend(from, to, w);

        /*
         * 4. Выпуск по типам изделий.
         */
        List<ProductionOutputRow> productionOutput =
                loadProductionOutput(from, to);

        /*
         * 5. Исполнители.
         */
        List<ExecutorRankingRow> executorRanking =
                loadExecutorRanking(from, to);

        /*
         * 6. Просрочки.
         */
        List<LateOperationRow> lateOperations =
                loadLateOperations(from, to);

        return new ActualPerformanceResult(
                kpi, unitStats, weeklyTrend, productionOutput,
                executorRanking, lateOperations, w, from, to);
    }

    private List<UnitOperationStats> loadUnitStats(
            LocalDate from,
            LocalDate to
    ) {
        List<ProductSerialOperationRepository.UnitActualRow> rows =
                operationRepository.aggregateByUnit(from, to);

        if (rows.isEmpty()) {
            return List.of();
        }

        /*
         * План по цехам — для расчёта utilization: факт / план.
         */
        Map<Long, BigDecimal> plannedByUnit = new HashMap<>();

        for (OrderScheduleRowRepository.UnitPlannedRow prow
                : scheduleRowRepository.aggregatePlannedByUnit(from, to)) {

            plannedByUnit.put(
                    prow.getUnitId(),
                    prow.getTotalLaborHours() == null
                            ? BigDecimal.ZERO
                            : prow.getTotalLaborHours()
            );
        }

        /*
         * Численность цеха — для «операций на человека в день»:
         * это честная мера интенсивности, в отличие от нормо-часов,
         * которых в факте нет.
         */
        Map<Long, Integer> employeesByUnit = countEmployeesByUnit();

        long workingDays = countWorkingDays(from, to);

        List<UnitOperationStats> result = new ArrayList<>();

        for (ProductSerialOperationRepository.UnitActualRow row : rows) {
            long done = row.getDoneOperations() == null
                    ? 0
                    : row.getDoneOperations();
            long serials = row.getUniqueSerials() == null
                    ? 0
                    : row.getUniqueSerials();
            long late = row.getLateOperations() == null
                    ? 0
                    : row.getLateOperations();
            long rework = row.getReworkOperations() == null
                    ? 0
                    : row.getReworkOperations();

            int employees = row.getUnitId() == null
                    ? 0
                    : employeesByUnit.getOrDefault(row.getUnitId(), 0);

            double perDay = workingDays > 0
                    ? (double) done / workingDays
                    : done;

            /*
             * Операций на человека в день: 1,0 — норма, больше —
             * цех работает плотнее одного исполнителя на операцию.
             */
            BigDecimal plannedHours = plannedByUnit
                    .getOrDefault(row.getUnitId(), BigDecimal.ZERO);

            BigDecimal plannedPerDay = plannedHours.signum() > 0
                    ? plannedHours.divide(
                            BigDecimal.valueOf(workingDays),
                            1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            /*
             * utilizationPercent — отношение факта к плану.
             * Больше 1,2 (120 %) — критическая перегрузка,
             * 1,0–1,2 — плотная работа, < 1,0 — цех работает
             * не на полную загрузку.
             */
            BigDecimal utilization;

            if (plannedHours.signum() > 0) {
                utilization = BigDecimal.valueOf(done)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(plannedHours, 2, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else {
                utilization = null;
            }

            result.add(new UnitOperationStats(
                    row.getUnitId(),
                    row.getUnitName() != null
                            ? row.getUnitName()
                            : "Без подразделения",
                    row.getServiceCode(),
                    row.getServiceName(),
                    (int) workingDays,
                    done,
                    serials,
                    late,
                    rework,
                    employees,
                    BigDecimal.valueOf(perDay)
                            .setScale(1, RoundingMode.HALF_UP),
                    plannedPerDay,
                    utilization
            ));
        }

        return result;
    }

    private List<WeeklyTrendRow> loadWeeklyTrend(
            LocalDate from,
            LocalDate to,
            int weeks
    ) {
        Map<LocalDate, Long> actualByDay = new HashMap<>();

        for (ProductSerialOperationRepository.DayCountRow row
                : operationRepository.actualCountByDay(from, to)) {

            actualByDay.put(
                    row.getDay(),
                    row.getDone() == null ? 0L : row.getDone()
            );
        }

        Map<LocalDate, Long> plannedByDay = new HashMap<>();

        for (ProductSerialOperationRepository.DayCountRow row
                : operationRepository.plannedCountByDay(from, to)) {

            plannedByDay.put(
                    row.getDay(),
                    row.getDone() == null ? 0L : row.getDone()
            );
        }

        WeekFields weekFields = WeekFields.of(DayOfWeek.MONDAY, 1);

        LocalDate firstMonday = from.with(weekFields.dayOfWeek(), 1);
        if (firstMonday.isBefore(from)) {
            firstMonday = from;
        }

        List<WeeklyTrendRow> result = new ArrayList<>();

        for (int wi = 0; wi < weeks; wi++) {
            LocalDate weekFrom = firstMonday.plusWeeks(wi);
            LocalDate weekTo = weekFrom.plusDays(4);

            long actual = 0;
            long planned = 0;

            for (LocalDate date = weekFrom;
                 !date.isAfter(weekTo);
                 date = date.plusDays(1)) {

                actual += actualByDay.getOrDefault(date, 0L);
                planned += plannedByDay.getOrDefault(date, 0L);
            }

            /*
             * Максимум за неделю — для масштаба бара.
             * 10 операций = 100 % (шкала 10 операций/нед).
             */
            long maxOps = Math.max(planned, actual);
            double scale = maxOps > 0 ? 100.0 / maxOps : 10.0;

            result.add(new WeeklyTrendRow(
                    weekFrom.get(weekFields.weekOfWeekBasedYear()),
                    weekFrom,
                    weekTo,
                    planned,
                    actual,
                    Math.min(100, planned * scale),
                    Math.min(100, actual * scale)
            ));
        }

        return result;
    }

    private List<ProductionOutputRow> loadProductionOutput(
            LocalDate from,
            LocalDate to
    ) {
        List<ProductSerialRepository.SerialOutputRow> rows =
                serialRepository.countCellReadyByClassifier(
                        from.atStartOfDay(),
                        to.plusDays(1).atStartOfDay()
                );

        List<ProductionOutputRow> result = new ArrayList<>();

        for (ProductSerialRepository.SerialOutputRow row : rows) {
            ProductType type = row.getProductType();

            /*
             * У датчика наименование берётся из каталога датчиков:
             * в классификаторе поле name хранит строку product_name.
             */
            String displayName = type == ProductType.SENSOR
                    && row.getSensorName() != null
                    ? row.getSensorName()
                    : row.getName();

            long produced = row.getProduced() == null
                    ? 0
                    : row.getProduced();

            result.add(new ProductionOutputRow(
                    row.getClassifierId(),
                    row.getCode(),
                    displayName,
                    type,
                    type != null ? type.getDisplayName() : "—",
                    produced
            ));
        }

        return result;
    }

    private List<ExecutorRankingRow> loadExecutorRanking(
            LocalDate from,
            LocalDate to
    ) {
        List<ProductSerialOperationRepository.ExecutorRow> rows =
                operationRepository.executorRanking(from, to);

        List<ExecutorRankingRow> result = new ArrayList<>();

        for (ProductSerialOperationRepository.ExecutorRow row : rows) {
            result.add(new ExecutorRankingRow(
                    row.getExecutorId(),
                    row.getExecutorName(),
                    row.getDoneOperations() == null
                            ? 0
                            : row.getDoneOperations(),
                    row.getLateOperations() == null
                            ? 0
                            : row.getLateOperations(),
                    row.getReworkOperations() == null
                            ? 0
                            : row.getReworkOperations()
            ));

            if (result.size() >= 10) {
                break;
            }
        }

        return result;
    }

    private List<LateOperationRow> loadLateOperations(
            LocalDate from,
            LocalDate to
    ) {
        List<ProductSerialOperationRepository.LateOperationRowView> rows =
                operationRepository.findLateOperations(from, to);

        List<LateOperationRow> result = new ArrayList<>();

        for (ProductSerialOperationRepository.LateOperationRowView row
                : rows) {

            long lateDays = ChronoUnit.DAYS.between(
                    row.getPlannedDate(),
                    row.getActualDate()
            );

            /*
             * Полный номер изделия: код классификатора + серия
             * в пять знаков, как в реестре серийников.
             */
            String serialNumber = row.getSerialNumber() == null
                    ? "—"
                    : (row.getClassifierCode() == null
                            ? ""
                            : String.valueOf(row.getClassifierCode()))
                        + String.format("%05d", row.getSerialNumber());

            result.add(new LateOperationRow(
                    serialNumber,
                    row.getOperationName() != null
                            ? row.getOperationName()
                            : "—",
                    row.getUnitName(),
                    row.getExecutorName(),
                    row.getPlannedDate(),
                    row.getActualDate(),
                    (int) Math.max(0, lateDays)
            ));

            if (result.size() >= LATE_LIMIT) {
                break;
            }
        }

        return result;
    }

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

    private static long countWorkingDays(LocalDate from, LocalDate to) {
        long count = 0;

        for (LocalDate date = from;
             !date.isAfter(to);
             date = date.plusDays(1)) {

            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }

        return Math.max(count, 1);
    }
}

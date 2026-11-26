package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.StaffOperationRow;
import ru.company.production.dto.StaffProductivityRow;
import ru.company.production.dto.StaffMemberView;
import ru.company.production.entity.WorkWithoutPlan;
import ru.company.production.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Статистика производительности сотрудников цеха.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkshopProductivityService {

    private final OrderScheduleRowRepository scheduleRowRepository;
    private final WorkWithoutPlanRepository workWithoutPlanRepository;
    private final EmployeeAbsenceRepository absenceRepository;
    private final HolidayDayRepository holidayRepository;
    private final WorkshopStaffService workshopStaffService;

    /** Норма часов в одном рабочем дне. */
    private static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");

    /**
     * Производительность всех сотрудников подразделения за период.
     */
    public List<StaffProductivityRow> findProductivity(
            Collection<Long> unitIds,
            LocalDate from,
            LocalDate to
    ) {
        List<StaffMemberView> employees =
                workshopStaffService.findStaff(unitIds);

        if (employees.isEmpty()) {
            return List.of();
        }

        Collection<Long> employeeIds =
                employees.stream().map(StaffMemberView::id).toList();

        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        /*
         * Назначенные часы — по дате плана.
         */
        Map<Long, BigDecimal> assignedMap = new HashMap<>();
        for (OrderScheduleRowRepository.HoursByEmployee h
                : scheduleRowRepository.sumAssignedHoursByEmployees(
                employeeIds, from, to)) {

            assignedMap.put(
                    h.getEmployeeId(),
                    h.getTotalHours() != null ? h.getTotalHours() : BigDecimal.ZERO
            );
        }

        /*
         * Закрытые часы — по дате проведения.
         */
        Map<Long, BigDecimal> closedMap = new HashMap<>();
        for (OrderScheduleRowRepository.HoursByEmployee h
                : scheduleRowRepository.sumClosedHoursByEmployees(
                employeeIds, fromTime, toTime)) {

            closedMap.put(
                    h.getEmployeeId(),
                    h.getTotalHours() != null ? h.getTotalHours() : BigDecimal.ZERO
            );
        }

        /*
         * Работа без плана — учитывается только в закрытых.
         */
        Map<Long, BigDecimal> wwMap = new HashMap<>();
        for (StaffMemberView emp : employees) {
            BigDecimal ww = workWithoutPlanRepository
                    .sumTotalHours(emp.id(), from, to);
            wwMap.put(emp.id(),
                    ww != null ? ww : BigDecimal.ZERO);
        }

        /*
         * Рабочие дни каждого сотрудника: календарь минус праздники
         * и отсутствия.
         */
        Map<Long, BigDecimal> workedMap = new HashMap<>();
        for (StaffMemberView emp : employees) {
            workedMap.put(emp.id(),
                    calculateWorkedDays(emp.id(), from, to));
        }

        /*
         * Сбор строк.
         */
        List<StaffProductivityRow> rows = new ArrayList<>();

        for (StaffMemberView emp : employees) {
            Long eid = emp.id();
            BigDecimal assigned = assignedMap.getOrDefault(eid, BigDecimal.ZERO);
            BigDecimal closed = closedMap.getOrDefault(eid, BigDecimal.ZERO);
            BigDecimal ww = wwMap.getOrDefault(eid, BigDecimal.ZERO);
            BigDecimal worked = workedMap.getOrDefault(eid, BigDecimal.ZERO);

            BigDecimal totalClosed = closed.add(ww);

            /*
             * Производительность: (закрытые / (рабочие дни × 8)) × 100.
             * > 100 — переработка, < 100 — недогруз.
             */
            BigDecimal productivity = BigDecimal.ZERO;
            if (worked.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal capacity = worked.multiply(HOURS_PER_DAY);
                productivity = totalClosed
                        .divide(capacity, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
            }

            rows.add(new StaffProductivityRow(
                    eid,
                    emp.fullName(),
                    assigned,
                    closed,
                    ww,
                    worked,
                    productivity
            ));
        }

        /*
         * Сортировка: по убыванию производительности.
         */
        rows.sort((a, b) -> b.productivity().compareTo(a.productivity()));
        return rows;
    }

    /**
     * Разрез «сотрудник × операция» за период.
     *
     * Берёт закрытые плановые операции и добавляет к ним «работу без
     * плана» как отдельную строку с текстовым именем операции.
     */
    public List<StaffOperationRow> findOperations(
            Collection<Long> unitIds,
            LocalDate from,
            LocalDate to
    ) {
        List<StaffMemberView> employees =
                workshopStaffService.findStaff(unitIds);

        if (employees.isEmpty()) {
            return List.of();
        }

        Collection<Long> employeeIds =
                employees.stream().map(StaffMemberView::id).toList();

        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        Map<Long, String> employeeNames = new HashMap<>();
        for (StaffMemberView emp : employees) {
            employeeNames.put(emp.id(), emp.fullName());
        }

        /*
         * Плановые закрытые операции.
         */
        List<StaffOperationRow> rows = new ArrayList<>();
        for (OrderScheduleRowRepository.HoursByEmployeeOperation h
                : scheduleRowRepository.sumClosedHoursByEmployeeOperation(
                employeeIds, fromTime, toTime)) {

            rows.add(new StaffOperationRow(
                    h.getEmployeeId(),
                    employeeNames.getOrDefault(h.getEmployeeId(), "—"),
                    h.getOperationName() != null ? h.getOperationName() : "—",
                    h.getRowCount() != null ? h.getRowCount() : 0L,
                    h.getTotalHours() != null ? h.getTotalHours() : BigDecimal.ZERO
            ));
        }

        /*
         * Работа без плана — отдельными строками того же разреза.
         *
         * Слияние по имени операции не делается: имя здесь свободный
         * текст, и совпадение с плановой операцией случайное.
         */
        for (OrderScheduleRowRepository.HoursByEmployeeOperation h
                : workWithoutPlanRepository.sumByEmployeeAndOperation(
                employeeIds, from, to)) {

            rows.add(new StaffOperationRow(
                    h.getEmployeeId(),
                    employeeNames.getOrDefault(h.getEmployeeId(), "—"),
                    h.getOperationName() != null ? h.getOperationName() : "—",
                    h.getRowCount() != null ? h.getRowCount() : 0L,
                    h.getTotalHours() != null ? h.getTotalHours() : BigDecimal.ZERO
            ));
        }

        return rows;
    }

    /**
     * Реестр записей «работа без плана» подразделения за период.
     */
    public List<WorkWithoutPlan> findWithoutPlanRecords(
            Collection<Long> unitIds,
            LocalDate from,
            LocalDate to
    ) {
        Collection<Long> employeeIds = employeeIds(unitIds);

        if (employeeIds.isEmpty()) {
            return List.of();
        }

        return workWithoutPlanRepository.findForEmployees(employeeIds, from, to);
    }

    /**
     * Идентификаторы сотрудников подразделения.
     */
    public Collection<Long> employeeIds(Collection<Long> unitIds) {
        return workshopStaffService.findStaff(unitIds).stream()
                .map(StaffMemberView::id)
                .toList();
    }

    /**
     * Рабочие дни сотрудника за период:
     * (календарные дни — выходные — праздники — отсутствия).
     */
    private BigDecimal calculateWorkedDays(Long employeeId, LocalDate from, LocalDate to) {
        long totalDays = 0;
        long weekends = 0;
        long holidays = 0;
        long absences = 0;

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            totalDays++;
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                weekends++;
            } else if (holidayRepository.existsByHolidayDate(d)) {
                holidays++;
            } else if (absenceRepository.existsByEmployeeIdAndAbsenceDate(
                    employeeId, d)) {
                absences++;
            }
        }

        long worked = totalDays - weekends - holidays - absences;
        return BigDecimal.valueOf(Math.max(0, worked));
    }
}

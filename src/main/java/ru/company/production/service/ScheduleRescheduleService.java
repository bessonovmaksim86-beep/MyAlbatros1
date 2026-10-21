package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.EmployeeAbsence;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.ExecutorDayLoadView;
import ru.company.production.repository.OrderScheduleRowRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Перенос непроведённых работ с прошедших дат.
 *
 * Экран распределения показывает работы внутри выбранного интервала
 * («сегодня — +2 недели»). Строка, чья дата плана уже прошла и которую
 * исполнитель не провёл, за этот левый край и выпадает: мастер её не
 * видит, работа остаётся незакрытой и незаметной. Такой строке
 * сдвигают planned_date на ближайший день, где у исполнителя есть
 * свободное время, а прежнюю дату оставляют в original_planned_date —
 * по ней видно, насколько работа просрочена.
 *
 * Правила поиска дня:
 *   • день рабочий (понедельник — пятница, см. {@link
 *     WorkingDaysCalculator});
 *   • у сотрудника в этот день нет отсутствия — перенос на день
 *     отпуска создал бы ту же невидимую работу через неделю;
 *   • занятость дня плюс трудоёмкость строки не превышает смену.
 *
 * Занятость считается по строкам графика того же исполнителя, поэтому
 * перенесённая работа не накладывается на уже запланированную:
 * накопленный остаток дня держится в памяти и учитывается для
 * следующих строк того же человека.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleRescheduleService {

    /** Трудоёмкость смены, человеко-часы. */
    private static final BigDecimal SHIFT_HOURS = new BigDecimal("8");

    /**
     * Сколько дней перебирать в поисках свободного. Дольше искать
     * бессмысленно: если у человека заняты два месяца подряд, работа
     * всё равно не влезет, а такой перебор — уже сигнал о том, что
     * план надо пересматривать диспетчеру, а не сдвигать строку.
     */
    private static final int MAX_SEARCH_DAYS = 60;

    /** Горизонт, за который берётся текущая занятость и отсутствия. */
    private static final int LOAD_HORIZON_DAYS = MAX_SEARCH_DAYS;

    private final OrderScheduleRowRepository scheduleRowRepo;
    private final EmployeeAbsenceRepository absenceRepo;
    private final WorkingDaysCalculator workingDays;

    /**
     * Перенос непроведённых строк подразделения.
     *
     * Пустой {@code unitIds} означает «нечего переносить»: у войдящего
     * руководителя без подразделения своего плана нет, а пустая
     * коллекция в JPQL-запросе {@code in} вернула бы ноль строк —
     * перенос молча не сделал бы ничего. Границу задаёт вызывающий:
     * вход руководителя переносит работы своего подразделения, а не
     * всего завода.
     */
    @Transactional
    public List<String> rescheduleOverdueRows(List<Long> unitIds) {
        return reschedule(LocalDate.now(), unitIds);
    }

    /**
     * Перенос с явной «сегодняшней» датой — нужно тестам: проверка
     * идёт по календарю, а заводить ради него часы в тесте нельзя.
     */
    @Transactional
    public List<String> reschedule(LocalDate today, List<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return List.of();
        }

        List<OrderScheduleRow> rows = scheduleRowRepo.findOverdueUncompleted(
                ScheduleRowStatus.CONFIRMED, today, unitIds
        );

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> executorIds = rows.stream()
                .map(OrderScheduleRow::getExecutor)
                .filter(executor -> executor != null)
                .map(AppUser::getId)
                .distinct()
                .toList();

        Map<Long, Map<LocalDate, BigDecimal>> busy =
                loadOf(executorIds, today);

        Set<String> absentDays = absentDays(today);

        List<String> moved = new ArrayList<>();

        for (OrderScheduleRow row : rows) {
            LocalDate target = targetDate(row, today, busy, absentDays);

            if (target == null) {
                continue;
            }

            if (target.isEqual(row.getPlannedDate())) {
                continue;
            }

            /*
             * Первая переноска сохраняет дату, которую назначил
             * планировщик. Повторный перенос перезаписывать её не
             * должен: иначе через месяц «изначально планируемая дата»
             * превратилась бы в дату последнего сдвига.
             */
            if (row.getOriginalPlannedDate() == null) {
                row.setOriginalPlannedDate(row.getPlannedDate());
            }

            LocalDate previous = row.getPlannedDate();
            row.setPlannedDate(target);

            AppUser executor = row.getExecutor();

            if (executor != null) {
                busy
                        .computeIfAbsent(executor.getId(), k -> new HashMap<>())
                        .merge(target, row.getLaborHours(), BigDecimal::add);
            }

            scheduleRowRepo.save(row);

            moved.add(describe(row, previous, target));
        }

        return moved;
    }

    /**
     * Ближайший день, куда строку можно положить.
     *
     * Строка без исполнителя ни к чьей загрузке не привязана — ей
     * достаточно ближайшего рабочего дня, и она попадёт в очередь
     * распределения.
     */
    private LocalDate targetDate(
            OrderScheduleRow row,
            LocalDate today,
            Map<Long, Map<LocalDate, BigDecimal>> busy,
            Set<String> absentDays
    ) {
        AppUser executor = row.getExecutor();

        if (executor == null) {
            return workingDays.nextWorkingDay(today);
        }

        Map<LocalDate, BigDecimal> days =
                busy.getOrDefault(executor.getId(), Map.of());

        LocalDate candidate = workingDays.nextWorkingDay(today);

        for (int step = 0; step < MAX_SEARCH_DAYS; step++) {
            if (!isAbsent(absentDays, executor.getId(), candidate)) {
                BigDecimal taken = days.getOrDefault(candidate, BigDecimal.ZERO);

                if (fits(taken, row.getLaborHours())) {
                    return candidate;
                }
            }

            candidate = workingDays.nextWorkingDay(candidate.plusDays(1));
        }

        return null;
    }

    /**
     * Занятость сотрудников по дням: строки графика, которые уже стоят
     * в плане. Считается одним запросом на всех, иначе перенос сотни
     * строк дал бы сотни обращений к базе.
     */
    private Map<Long, Map<LocalDate, BigDecimal>> loadOf(
            List<Long> executorIds,
            LocalDate today
    ) {
        if (executorIds.isEmpty()) {
            return new HashMap<>();
        }

        List<ExecutorDayLoadView> loads = scheduleRowRepo.findExecutorLoad(
                ScheduleRowStatus.CONFIRMED,
                executorIds,
                today,
                today.plusDays(LOAD_HORIZON_DAYS)
        );

        Map<Long, Map<LocalDate, BigDecimal>> busy = new HashMap<>();

        for (ExecutorDayLoadView load : loads) {
            busy
                    .computeIfAbsent(load.getExecutorId(), k -> new HashMap<>())
                    .merge(
                            load.getPlannedDate(),
                            load.getHours() == null
                                    ? BigDecimal.ZERO
                                    : load.getHours(),
                            BigDecimal::add
                    );
        }

        return busy;
    }

    /**
     * Дни отсутствий в виде «сотрудник|дата».
     *
     * Пакетная выборка за горизонт: перебирать даты и людей запросами
     * внутри подбора дня значило бы thousands обращений к базе на
     * один перенос.
     */
    private Set<String> absentDays(LocalDate today) {
        List<EmployeeAbsence> absences = absenceRepo.findByAbsenceDateBetween(
                today,
                today.plusDays(LOAD_HORIZON_DAYS)
        );

        Set<String> days = new HashSet<>();

        for (EmployeeAbsence absence : absences) {
            if (absence.getEmployee() == null) {
                continue;
            }

            days.add(absence.getEmployee().getId()
                    + "|" + absence.getAbsenceDate());
        }

        return days;
    }

    private static boolean isAbsent(
            Set<String> absentDays,
            Long executorId,
            LocalDate date
    ) {
        return absentDays.contains(executorId + "|" + date);
    }

    /** Влезает ли работа в остаток смены. */
    private static boolean fits(BigDecimal taken, BigDecimal hours) {
        BigDecimal labor = hours == null ? BigDecimal.ZERO : hours;

        return taken.add(labor).compareTo(SHIFT_HOURS) <= 0;
    }

    /**
     * Строка лога: что, откуда и куда перенесено.
     *
     * Лог — единственный след переноса для тех строк, что руководитель
     * так и не откроет, поэтому в подписи есть и номер основания, и
     * фамилия: без них строку не найти в плане.
     */
    private static String describe(
            OrderScheduleRow row,
            LocalDate from,
            LocalDate to
    ) {
        AppUser executor = row.getExecutor();

        return "%s · %s: %s → %s (%s)".formatted(
                row.getBaseNumber(),
                operationName(row),
                from,
                to,
                executor == null
                        ? "не распределена"
                        : WorkshopStaffService.displayName(executor)
        );
    }

    private static String operationName(OrderScheduleRow row) {
        if (row.getOperation() == null
                || row.getOperation().getOperationName() == null) {
            return "операция";
        }

        return row.getOperation().getOperationName().getName();
    }
}

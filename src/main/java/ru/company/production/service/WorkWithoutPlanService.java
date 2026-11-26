package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.WorkWithoutPlanForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.WorkWithoutPlan;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.HolidayDayRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkWithoutPlanRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Работа без плана: ручная выработка вне производственного плана.
 *
 * ЗАЧЕМ ОТДЕЛЬНАЯ ТАБЛИЦА. Строка плана (order_schedule_rows) —
 * обязательство: она участвует в распределении, висит в «Мои
 * операции», попадает в загрузку подразделения и в закрытие изделия.
 * Работа без плана ничего из этого не делает: её вносит руководитель
 * цеха постфактум за операцию, которой в плане не было. Поэтому она
 * живёт в отдельной таблице и учитывается только в статистике
 * производительности подразделения.
 *
 * ГРАНИЦЫ ДАТЫ. Дату берут только текущего месяца: за прошлый месяц
 * статистику закрывают, и задним числом менять её нельзя, а будущая
 * дата описала бы работу, которой ещё не было.
 */
@Service
@RequiredArgsConstructor
public class WorkWithoutPlanService {

    /** Норма часов в рабочем дне. */
    public static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");

    private final WorkWithoutPlanRepository workWithoutPlanRepository;
    private final EmployeeAbsenceRepository absenceRepository;
    private final HolidayDayRepository holidayRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final UserRepository userRepository;
    private final WorkshopStaffService staffService;

    /**
     * Проверить данные и занести работу без плана.
     *
     * Отклоняется {@link IllegalArgumentException} с текстом,
     * пригодным для flash-сообщения: контроллер цеховых разделов
     * показывает сообщение сервиса пользователю как есть.
     */
    @Transactional
    public WorkWithoutPlan save(WorkWithoutPlanForm form, AppUser supervisor) {
        if (form == null) {
            throw new IllegalArgumentException("Заполните форму");
        }

        if (form.employeeId() == null) {
            throw new IllegalArgumentException("Выберите сотрудника");
        }

        String operationName = form.operationName() == null
                ? ""
                : form.operationName().trim();

        if (operationName.isEmpty()) {
            throw new IllegalArgumentException("Укажите наименование операции");
        }

        if (operationName.length() > 255) {
            throw new IllegalArgumentException(
                    "Наименование операции длиннее 255 знаков");
        }

        if (form.normHours() == null
                || form.normHours().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Норма должна быть больше нуля, в долях часа");
        }

        if (form.quantity() == null || form.quantity() <= 0) {
            throw new IllegalArgumentException("Количество должно быть больше нуля");
        }

        LocalDate workDate = form.workDate();

        if (workDate == null) {
            throw new IllegalArgumentException("Укажите дату выполнения");
        }

        AppUser employee = userRepository.findById(form.employeeId())
                .orElseThrow(() ->
                        new EntityNotFoundException("Сотрудник не найден"));

        /*
         * Сотрудник обязан принадлежать подразделению руководителя:
         * иначе он внесёт выработку чужого цеха, и она попадёт в
         * статистику этого цеха.
         */
        staffService.requireSameWorkshop(employee, supervisor);

        checkDate(workDate, employee);

        BigDecimal hours = form.normHours()
                .multiply(BigDecimal.valueOf(form.quantity()));

        checkDayCapacity(workDate, employee, hours);

        WorkWithoutPlan record = new WorkWithoutPlan();
        record.setEmployee(employee);
        record.setOperationName(operationName);
        record.setNormHours(form.normHours());
        record.setQuantity(form.quantity());
        record.setWorkDate(workDate);
        record.setCreatedBy(supervisor);

        return workWithoutPlanRepository.save(record);
    }

    /**
     * Удаление записи: ошибка ввода исправляется новой строкой, а не
     * правкой старой — история выработки не должна перезаписываться.
     */
    @Transactional
    public void delete(Long id) {
        WorkWithoutPlan record = workWithoutPlanRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException("Запись не найдена"));

        workWithoutPlanRepository.delete(record);
    }

    /**
     * Дата: только текущий месяц, рабочий день, сотрудник не в отсутствии.
     */
    private void checkDate(LocalDate workDate, AppUser employee) {
        LocalDate today = LocalDate.now();

        if (workDate.isAfter(today)) {
            throw new IllegalArgumentException(
                    "Дата не может быть в будущем: сегодня "
                            + formatDate(today));
        }

        LocalDate monthStart = today.withDayOfMonth(1);

        if (workDate.isBefore(monthStart)) {
            throw new IllegalArgumentException(
                    "Работу без плана вносят только текущим месяцем: "
                            + "прошлые периоды закрыты");
        }

        DayOfWeek day = workDate.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            throw new IllegalArgumentException(
                    formatDate(workDate)
                            + " — выходной день, работа не планируется");
        }

        if (holidayRepository.existsByHolidayDate(workDate)) {
            throw new IllegalArgumentException(
                    formatDate(workDate)
                            + " — праздничный день по производственному календарю");
        }

        if (absenceRepository.existsByEmployeeIdAndAbsenceDate(
                employee.getId(), workDate)) {
            throw new IllegalArgumentException(
                    WorkshopStaffService.displayName(employee)
                            + " в этот день отсутствует по календарю");
        }
    }

    /**
     * Выработка за день не должна превышать рабочий день.
     *
     * Считаются уже проведённые плановые операции этого сотрудника на
     * эту дату и ранее внесённые работы без плана: без второй
     * составляющей ограничение обходились бы повторным вводом формы.
     */
    private void checkDayCapacity(LocalDate workDate,
                                  AppUser employee,
                                  BigDecimal hours) {
        BigDecimal used = BigDecimal.ZERO;

        BigDecimal plan = scheduleRowRepository.sumClosedHoursByEmployee(
                employee.getId(),
                workDate.atStartOfDay(),
                workDate.plusDays(1).atStartOfDay());

        if (plan != null) {
            used = used.add(plan);
        }

        BigDecimal outside = workWithoutPlanRepository.sumTotalHoursForDay(
                employee.getId(), workDate);

        if (outside != null) {
            used = used.add(outside);
        }

        BigDecimal left = HOURS_PER_DAY.subtract(used);

        if (left.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    WorkshopStaffService.displayName(employee)
                            + " уже закрыт(а) на "
                            + scale(used) + " ч в этот день: рабочий день исчерпан");
        }

        if (hours.compareTo(left) > 0) {
            throw new IllegalArgumentException(
                    "Работа не вписывается в рабочий день: осталось "
                            + scale(left) + " ч, заявлено " + scale(hours) + " ч");
        }
    }

    private static String scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static String formatDate(LocalDate date) {
        return date == null ? "—" : date.format(DATE_FORMATTER);
    }
}

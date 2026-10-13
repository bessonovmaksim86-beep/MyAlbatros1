package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AbsenceType;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.EmployeeAbsence;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.UserRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Производственный календарь цеха: отсутствия и нерабочие дни.
 *
 * Видимость ограничена тем же цехом, что и список сотрудников
 * (см. {@link WorkshopStaffService}): отмечать отсутствие может только
 * руководитель того подразделения, где сотрудник работает.
 */
@Service
@RequiredArgsConstructor
public class WorkshopCalendarService {

    /** Максимум дней в одной отметке: защита от случайной отправки года. */
    private static final int MAX_RANGE_DAYS = 366;

    private final UserRepository userRepository;
    private final EmployeeAbsenceRepository absenceRepository;
    private final WorkshopStaffService staffService;

    /**
     * Календарь месяца: сотрудник → дата → вид отсутствия.
     *
     * Возвращается связанных по сотрудникам порядок, чтобы таблица
     * календаря шла в том же порядке, что и список цеха.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<LocalDate, EmployeeAbsence>> buildMonth(
            List<AppUser> employees,
            YearMonth month
    ) {
        Map<Long, Map<LocalDate, EmployeeAbsence>> calendar =
                new LinkedHashMap<>();

        if (employees == null || employees.isEmpty()) {
            return calendar;
        }

        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        for (AppUser employee : employees) {
            Map<LocalDate, EmployeeAbsence> days = new LinkedHashMap<>();

            for (EmployeeAbsence absence
                    : absenceRepository.findInRange(
                    employee.getId(),
                    from,
                    to
            )) {
                days.put(absence.getAbsenceDate(), absence);
            }

            calendar.put(employee.getId(), days);
        }

        return calendar;
    }

    /**
     * Отметка отсутствия на день, диапазон или весь список сотрудников.
     *
     * Диапазон раскладывается на отдельные записи: UNIQUE
     * (employee_id, absence_date) в схеме тогда однозначно запрещает
     * пересечение двух видов отсутствия в один день, и перечёркивать
     * уже поставленную отметку приходится осознанно.
     */
    @Transactional
    public int mark(
            List<Long> employeeIds,
            LocalDate from,
            LocalDate to,
            AbsenceType absenceType,
            String note,
            boolean replaceExisting,
            AppUser supervisor
    ) {
        if (absenceType == null) {
            throw new IllegalArgumentException("Выберите вид отсутствия");
        }

        /*
         * Нерабочий день сотруднику ставит справочник администратора:
         * он один на всё предприятие. Разрешить мастеру отмечать его
         * в своём цехе — значит получить расхождение календарей, когда
         * в одном цехе праздник проставлен, а в соседнем нет.
         */
        if (absenceType == AbsenceType.DAY_OFF) {
            throw new IllegalArgumentException(
                    "Выходные и праздничные дни задаёт администратор "
                            + "в разделе «Выходные и праздники»"
            );
        }

        if (from == null) {
            throw new IllegalArgumentException("Укажите начальную дату");
        }

        LocalDate last = to == null ? from : to;

        if (last.isBefore(from)) {
            throw new IllegalArgumentException(
                    "Конечная дата раньше начальной"
            );
        }

        if (ChronoUnit.DAYS.between(from, last) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Одна отметка не может превышать "
                            + MAX_RANGE_DAYS
                            + " дней"
            );
        }

        List<LocalDate> dates = datesBetween(from, last);
        List<AppUser> employees = requireEmployees(employeeIds, supervisor);

        int marked = 0;

        for (AppUser employee : employees) {
            marked += markDates(employee, dates, absenceType, note,
                    replaceExisting);
        }

        return marked;
    }

    /**
     * Снятие отметок за диапазон: удаляются только реально стоявшие дни,
     * поэтому «пустое» удаление не выглядит успешным действием.
     */
    @Transactional
    public int unmark(
            List<Long> employeeIds,
            LocalDate from,
            LocalDate to,
            AppUser supervisor
    ) {
        if (from == null) {
            throw new IllegalArgumentException("Укажите начальную дату");
        }

        LocalDate last = to == null ? from : to;

        if (last.isBefore(from)) {
            throw new IllegalArgumentException(
                    "Конечная дата раньше начальной"
            );
        }

        Collection<LocalDate> dates = datesBetween(from, last);

        int removed = 0;

        for (AppUser employee : requireEmployees(employeeIds, supervisor)) {
            removed += absenceRepository.deleteOnDates(
                    employee.getId(),
                    dates
            );
        }

        absenceRepository.flush();

        return removed;
    }

    /**
     * Дни месяца, на которые у цеха есть отметки, — нужно для сводки
     * «сколько дней отсутствия в этом месяце».
     */
    @Transactional(readOnly = true)
    public int countMonthDays(
            Map<Long, Map<LocalDate, EmployeeAbsence>> calendar
    ) {
        Set<LocalDate> days = new LinkedHashSet<>();

        calendar.values()
                .forEach(daysByEmployee -> days.addAll(daysByEmployee.keySet()));

        return days.size();
    }

    /**
     * Проставляет отметку списку сотрудников.
     *
     * Метод публичный: нерабочие дни справочника администратора
     * раскладываются по учётным записям тем же порядком — со снятием
     * конфликта и перезаписью только по явному согласию. Держать вторую
     * копию этого порядка в админском сервисе означало бы разъехаться
     * правилам при первой же правке.
     */
    @Transactional
    public int markDatesForEmployees(
            List<AppUser> employees,
            List<LocalDate> dates,
            AbsenceType absenceType,
            String note,
            boolean replaceExisting
    ) {
        int marked = 0;

        for (AppUser employee : employees) {
            marked += markDates(
                    employee,
                    dates,
                    absenceType,
                    note,
                    replaceExisting
            );
        }

        return marked;
    }

    /**
     * Подотчётные сотрудники: выбранные или весь цех, если выбор пуст.
     *
     * Пустой выбор означает «весь цех» потому, что праздничные и
     * выходные дни задают именно производству целиком, и отмечать их
     * перебором сотрудников было бы долго.
     *
     * Проверка цеха выполняется для каждого: форма позволяет отметить
     * сразу несколько сотрудников, и чужой сотрудник не должен
     * «проскользнуть» вместе со своими.
     */
    private List<AppUser> requireEmployees(
            List<Long> employeeIds,
            AppUser supervisor
    ) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            List<AppUser> all = staffService.findEmployees(
                    staffService.unitScope(supervisor)
            );

            if (all.isEmpty()) {
                throw new IllegalArgumentException(
                        "В вашем подразделении нет сотрудников"
                );
            }

            return all;
        }

        List<AppUser> employees = new ArrayList<>();

        for (Long employeeId : employeeIds.stream().distinct().toList()) {
            if (employeeId == null) {
                continue;
            }

            AppUser employee = userRepository.findById(employeeId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Сотрудник не найден"
                    ));

            staffService.requireSameWorkshop(employee, supervisor);
            employees.add(employee);
        }

        if (employees.isEmpty()) {
            throw new IllegalArgumentException(
                    "В вашем цехе нет сотрудников"
            );
        }

        return employees;
    }

    /**
     * Запись отметок одного сотрудника: сначала конфликт и перезапись,
     * затем вставка, чтобы UNIQUE (employee_id, absence_date) не
     * ловился посреди сохранений.
     */
    private int markDates(
            AppUser employee,
            List<LocalDate> dates,
            AbsenceType absenceType,
            String note,
            boolean replaceExisting
    ) {
        List<EmployeeAbsence> existing =
                absenceRepository.findExistingOnDates(employee.getId(), dates);

        boolean conflict = existing.stream()
                .anyMatch(absence -> absence.getAbsenceType() != absenceType);

        if (conflict && !replaceExisting) {
            throw new IllegalArgumentException(
                    "Среди выбранных дней уже есть отметки другого вида. "
                            + "Поставьте «Перезаписать», чтобы заменить их "
                            + "на «" + absenceType.getDisplayName() + "»"
            );
        }

        if (!existing.isEmpty()) {
            absenceRepository.deleteOnDates(
                    employee.getId(),
                    existing.stream().map(EmployeeAbsence::getAbsenceDate)
                            .toList()
            );
            absenceRepository.flush();
        }

        String trimmedNote = note == null || note.isBlank()
                ? null
                : note.trim();

        List<EmployeeAbsence> absences = new ArrayList<>();

        for (LocalDate date : dates) {
            EmployeeAbsence absence = new EmployeeAbsence();

            absence.setEmployee(employee);
            absence.setAbsenceDate(date);
            absence.setAbsenceType(absenceType);
            absence.setNote(trimmedNote);

            absences.add(absence);
        }

        absenceRepository.saveAll(absences);
        absenceRepository.flush();

        return absences.size();
    }

    private static List<LocalDate> datesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            dates.add(date);
        }

        return dates;
    }
}

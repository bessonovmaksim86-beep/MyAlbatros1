package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AbsenceType;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.HolidayDay;
import ru.company.production.entity.HolidayKind;
import ru.company.production.entity.Role;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.HolidayDayRepository;
import ru.company.production.repository.UserRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Справочник выходных и праздничных дней и его влияние на календари.
 *
 * Ведёт администратор: нерабочий день один на всё предприятие, поэтому
 * он заводится один раз и отмечается сразу у всех сотрудников. До этого
 * каждый руководитель цеха проставлял выходные сам, и календари цехов
 * расходились уже на первом пропущенном дне.
 *
 * Отметки по сотрудникам не дублируют правила: они раскладываются
 * тем же {@link WorkshopCalendarService}, что и отметки отсутствия,
 * поэтому поведение при конфликте с отпуском одинаково в обоих разделах.
 */
@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayDayRepository holidayRepository;
    private final UserRepository userRepository;
    private final EmployeeAbsenceRepository absenceRepository;
    private final WorkshopCalendarService calendarService;

    /**
     * Дни справочника за месяц — таблица администратора и подсветка
     * нерабочих дней в календаре.
     */
    @Transactional(readOnly = true)
    public List<HolidayDay> findByMonth(YearMonth month) {
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(
                month.atDay(1),
                month.atEndOfMonth()
        );
    }

    @Transactional(readOnly = true)
    public long countByMonth(YearMonth month) {
        return holidayRepository.countByHolidayDateBetween(
                month.atDay(1),
                month.atEndOfMonth()
        );
    }

    @Transactional(readOnly = true)
    public long count() {
        return holidayRepository.count();
    }

    /**
     * Добавление нерабочего дня: запись справочника плюс отметки всем
     * действующим сотрудникам.
     *
     * @return число проставленных отметок — им и отчитываемся, потому
     *         что «добавлен один день» без этой цифры не объясняет
     *         администратору, затронуто ли предприятие целиком
     */
    @Transactional
    public int add(
            LocalDate date,
            HolidayKind kind,
            String name,
            boolean replaceExisting
    ) {
        if (date == null) {
            throw new IllegalArgumentException("Укажите дату");
        }

        if (kind == null) {
            throw new IllegalArgumentException("Выберите вид дня");
        }

        if (holidayRepository.existsByHolidayDate(date)) {
            throw new IllegalArgumentException(
                    "На "
                            + date
                            + " нерабочий день уже заведён: сначала удалите его"
            );
        }

        HolidayDay holiday = new HolidayDay();

        holiday.setHolidayDate(date);
        holiday.setKind(kind);
        holiday.setName(title(name, kind, date));

        /*
         * Отметки создаются до фиксации справочника: если по выбранной
         * дате у кого-то стоит отпуск и перезапись не подтверждена,
         * транзакция откатится целиком и в справочнике не останется
         * дня без отметок.
         */
        int marked = markEmployees(List.of(date), replaceExisting);

        try {
            holidayRepository.saveAndFlush(holiday);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "Такой день уже есть в справочнике",
                    exception
            );
        }

        return marked;
    }

    /**
     * Удаление дня из справочника и снятие отметок «Выходной» по этой
     * дате. Отметки других видов не затрагиваются: снятие праздника не
     * должно стирать чужой отпуск.
     */
    @Transactional
    public int remove(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("День не указан");
        }

        HolidayDay holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Нерабочий день не найден"
                ));

        LocalDate date = holiday.getHolidayDate();

        holidayRepository.delete(holiday);
        holidayRepository.flush();

        int removed = 0;

        for (AppUser employee : activeEmployees()) {
            removed += absenceRepository.deleteDayOffOnDate(
                    employee.getId(),
                    date
            );
        }

        absenceRepository.flush();

        return removed;
    }

    /**
     * Добавляет в справочник все субботы и воскресенья месяца.
     *
     * Даты, которые администратор завёл вручную (перенесённые праздники
     * на будни, рабочие субботы), пропускаются: UNIQUE по дате защитил
     * бы вставку ошибкой, а понятнее просто оставить уже заданное.
     */
    @Transactional
    public int addMonthWeekends(
            YearMonth month,
            boolean replaceExisting
    ) {
        List<LocalDate> weekends = new ArrayList<>();

        for (
                LocalDate date = month.atDay(1);
                !date.isAfter(month.atEndOfMonth());
                date = date.plusDays(1)
        ) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                continue;
            }

            if (holidayRepository.existsByHolidayDate(date)) {
                continue;
            }

            weekends.add(date);
        }

        if (weekends.isEmpty()) {
            throw new IllegalArgumentException(
                    "Все выходные " + month.getMonth().getDisplayName(
                            java.time.format.TextStyle.FULL_STANDALONE,
                            java.util.Locale.forLanguageTag("ru")
                    ) + " уже в справочнике"
            );
        }

        int marked = markEmployees(weekends, replaceExisting);

        List<HolidayDay> holidays = new ArrayList<>();

        for (LocalDate date : weekends) {
            HolidayDay holiday = new HolidayDay();

            holiday.setHolidayDate(date);
            holiday.setKind(HolidayKind.DAY_OFF);
            holiday.setName(title(null, HolidayKind.DAY_OFF, date));

            holidays.add(holiday);
        }

        holidayRepository.saveAll(holidays);
        holidayRepository.flush();

        return marked;
    }

    /**
     * Отметки «Выходной» выбранному набору дат у всех действующих
     * сотрудников, кроме администраторов базы: у них нет цеха и их
     * отсутствие в производственном календаре считать не нужно.
     */
    private int markEmployees(List<LocalDate> dates, boolean replaceExisting) {
        List<AppUser> employees = activeEmployees();

        if (employees.isEmpty()) {
            throw new IllegalArgumentException(
                    "В системе нет ни одного действующего сотрудника"
            );
        }

        return calendarService.markDatesForEmployees(
                employees,
                dates,
                AbsenceType.DAY_OFF,
                null,
                replaceExisting
        );
    }

    private List<AppUser> activeEmployees() {
        return userRepository.findByActiveTrueOrderByFullNameAscUsernameAsc()
                .stream()
                .filter(user -> user.getRole() != Role.ADMIN)
                .toList();
    }

    /**
     * Подпись дня: своё название, иначе — вид и дата. Название попало бы
     * в подсказку календаря, поэтому пустая строка недопустима.
     */
    private static String title(
            String name,
            HolidayKind kind,
            LocalDate date
    ) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }

        return kind.getDisplayName() + " " + date;
    }
}

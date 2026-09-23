package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.AbsenceForm;
import ru.company.production.dto.WorkshopCalendarCell;
import ru.company.production.dto.WorkshopCalendarRow;
import ru.company.production.entity.AbsenceType;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.EmployeeAbsence;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopCalendarService;
import ru.company.production.service.WorkshopStaffService;

import java.beans.PropertyEditorSupport;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Руководитель цеха: производственный календарь — отсутствия
 * сотрудников и нерабочие дни.
 *
 * Календарь показывает тот же список цеха, что и раздел «Сотрудники»,
 * поэтому сотрудник не может оказаться отмеченным в чужом цехе:
 * границу проверяет {@link WorkshopCalendarService} на сохранении.
 */
@Controller
@RequestMapping("/workshop/calendar")
@RequiredArgsConstructor
public class WorkshopCalendarController {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final DateTimeFormatter MONTH_PARAM =
            DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Ширина сетки календаря в ячейках: 31 позиция, чтобы таблица не
     * меняла ширину между месяцами. Дни после конца месяца — пустые
     * ячейки без даты.
     */
    private static final int GRID_WIDTH = 31;

    private final WorkshopCalendarService calendarService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    /**
     * Календарь месяца. Месяц задаётся параметром {@code month=yyyy-MM};
     * без него или при неразобранном значении показывается текущий.
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(required = false) String month,
            @ModelAttribute("absenceForm") AbsenceForm absenceForm,
            Authentication authentication,
            Model model
    ) {
        fillIndexModel(authentication, parseMonth(month), model);

        model.addAttribute("activePage", "calendar");

        return "workshop/calendar/index";
    }

    /**
     * Отметка отсутствия на день, диапазон дней или выбранных сотрудников.
     */
    @PostMapping("/mark")
    public String mark(
            @ModelAttribute("absenceForm") AbsenceForm absenceForm,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            int marked = calendarService.mark(
                    toEmployeeIds(absenceForm.getEmployeeIds()),
                    absenceForm.getFromDate(),
                    absenceForm.getToDate(),
                    parseAbsenceType(absenceForm.getAbsenceType()),
                    absenceForm.getNote(),
                    absenceForm.isReplaceExisting(),
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Проставлено " + marked
                            + plural(marked, " отметка", " отметки", " отметок")
            );
        } catch (
                IllegalArgumentException
                | EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            /*
             * Введённые значения возвращаются flash-атрибутами: страница
             * после redirect пересобирается, и без них форма обнулилась бы
             * ровно в тот момент, когда пользователю нужно что-то исправить.
             */
            redirectAttributes.addFlashAttribute("absenceForm", absenceForm);
        }

        return "redirect:" + monthRedirect(absenceForm.getFromDate());
    }

    /**
     * Снятие отметок за день или диапазон дней.
     */
    @PostMapping("/unmark")
    public String unmark(
            @ModelAttribute("absenceForm") AbsenceForm absenceForm,
            @RequestParam(required = false) String month,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            int removed = calendarService.unmark(
                    toEmployeeIds(absenceForm.getEmployeeIds()),
                    absenceForm.getFromDate(),
                    absenceForm.getToDate(),
                    currentUser(authentication)
            );

            if (removed == 0) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "На выбранные даты отметок не было"
                );
            } else {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Снято " + removed
                                + plural(removed, " отметка", " отметки",
                                " отметок")
                );
            }
        } catch (
                IllegalArgumentException
                | EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:" + monthRedirect(
                absenceForm.getFromDate() == null
                        ? parseMonth(month).atDay(1)
                        : absenceForm.getFromDate()
        );
    }

    private void fillIndexModel(
            Authentication authentication,
            YearMonth month,
            Model model
    ) {
        Long unitId = staffService.workshopUnitId(currentUser(authentication));

        List<AppUser> employees = staffService.findEmployees(unitId);

        Map<Long, Map<LocalDate, EmployeeAbsence>> calendar =
                calendarService.buildMonth(employees, month);

        List<WorkshopCalendarRow> rows = new ArrayList<>();

        for (AppUser employee : employees) {
            Map<LocalDate, EmployeeAbsence> absences =
                    calendar.getOrDefault(employee.getId(), Map.of());

            rows.add(new WorkshopCalendarRow(
                    employee.getId(),
                    WorkshopStaffService.displayName(employee),
                    employee.getSpecialty(),
                    cells(month, absences),
                    absences.size()
            ));
        }

        Locale russian = Locale.forLanguageTag("ru");

        model.addAttribute("rows", rows);
        model.addAttribute("header", headerCells(month));
        model.addAttribute("employees", employees);
        model.addAttribute("absenceTypes", AbsenceType.values());
        /*
         * В форме мастера доступны только виды отсутствия людей.
         * «Выходной» из перечисления исключён: нерабочие дни задаёт
         * справочник администратора на всё предприятие, и отметка
         * такого дня одним цехом разошлась бы с календарями остальных.
         */
        model.addAttribute(
                "markableAbsenceTypes",
                Arrays.stream(AbsenceType.values())
                        .filter(type -> type != AbsenceType.DAY_OFF)
                        .toList()
        );
        model.addAttribute("month", month);
        model.addAttribute(
                "monthLabel",
                month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, russian)
        );
        model.addAttribute(
                "weekendCount",
                weekendsInMonth(month)
        );
        model.addAttribute(
                "previousMonthLabel",
                month.minusMonths(1).getMonth()
                        .getDisplayName(TextStyle.SHORT_STANDALONE, russian)
        );
        model.addAttribute(
                "nextMonthLabel",
                month.plusMonths(1).getMonth()
                        .getDisplayName(TextStyle.SHORT_STANDALONE, russian)
        );
        model.addAttribute("monthParam", month.format(MONTH_PARAM));
        model.addAttribute(
                "previousMonthParam",
                month.minusMonths(1).format(MONTH_PARAM)
        );
        model.addAttribute(
                "nextMonthParam",
                month.plusMonths(1).format(MONTH_PARAM)
        );
        model.addAttribute("daysInMonth", month.lengthOfMonth());
        model.addAttribute(
                "absenceDaysTotal",
                calendarService.countMonthDays(calendar)
        );
    }

    /**
     * Шапка сетки: те же 31 позиция, что и строки сотрудников, иначе
     * номера дней разъедутся с ячейками.
     */
    private static List<WorkshopCalendarCell> headerCells(YearMonth month) {
        return cells(month, Map.of());
    }

    private static List<WorkshopCalendarCell> cells(
            YearMonth month,
            Map<LocalDate, EmployeeAbsence> absences
    ) {
        LocalDate today = LocalDate.now();

        List<WorkshopCalendarCell> cells = new ArrayList<>(GRID_WIDTH);

        for (int day = 1; day <= GRID_WIDTH; day++) {
            if (day > month.lengthOfMonth()) {
                cells.add(new WorkshopCalendarCell(
                        0, null, false, false, null, null, null
                ));
                continue;
            }

            LocalDate date = month.atDay(day);
            EmployeeAbsence absence = absences.get(date);

            cells.add(new WorkshopCalendarCell(
                    day,
                    date.format(RU_DATE),
                    date.equals(today),
                    date.getDayOfWeek() == DayOfWeek.SATURDAY
                            || date.getDayOfWeek() == DayOfWeek.SUNDAY,
                    absence == null
                            ? null
                            : "is-" + absence.getAbsenceType().getCssSuffix(),
                    absence == null
                            ? null
                            : absence.getAbsenceType().getDisplayName()
                                    .substring(0, 1),
                    absence == null
                            ? date.format(RU_DATE)
                            : date.format(RU_DATE) + " — "
                                    + absence.getAbsenceType().getDisplayName()
                                    + (absence.getNote() == null
                                    ? ""
                                    : ". " + absence.getNote())
            ));
        }

        return cells;
    }

    /**
     * Число суббот и воскресений месяца — нужно, чтобы руководитель
     * видел, сколько отметок добавит массовая расстановка выходных.
     */
    private static int weekendsInMonth(YearMonth month) {
        int weekends = 0;

        for (
                LocalDate date = month.atDay(1);
                !date.isAfter(month.atEndOfMonth());
                date = date.plusDays(1)
        ) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY) {

                weekends++;
            }
        }

        return weekends;
    }

    /**
     * Идентификаторы из множественного select: пустые значения
     * отбрасываются, неразобранное значение считается ошибкой формы.
     */
    private static List<Long> toEmployeeIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>();

        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }

            try {
                ids.add(Long.valueOf(value.trim()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Некорректно выбран сотрудник"
                );
            }
        }

        return ids;
    }

    private static YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now();
        }

        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return YearMonth.now();
        }
    }

    private static AbsenceType parseAbsenceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return AbsenceType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Неизвестный вид отсутствия: " + value
            );
        }
    }

    /**
     * Возврат к месяцу, из которого пришла дата: отметка на стыке
     * месяцев иначе уводила бы руководителя в текущий месяц, и он не
     * увидел бы результат своего действия.
     */
    private static String monthRedirect(LocalDate anchor) {
        return "/workshop/calendar?month="
                + YearMonth.from(anchor == null ? LocalDate.now() : anchor)
                        .format(MONTH_PARAM);
    }

    private static String plural(
            int value,
            String one,
            String few,
            String many
    ) {
        int mod10 = Math.abs(value) % 10;
        int mod100 = Math.abs(value) % 100;

        if (mod10 == 1 && mod100 != 11) {
            return one;
        }

        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }

        return many;
    }

    /*
     * Подразделение руководителя определяет видимость сотрудников,
     * поэтому связи загружаются вместе с учётной записью.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись руководителя не найдена"
                ));
    }

    /**
     * Даты форм календаря принимаются в принятом в системе формате
     * дд.мм.гггг — тот же редактор, что и в заказах диспетчерского
     * отдела.
     */
    @InitBinder("absenceForm")
    public void bindAbsenceForm(WebDataBinder binder) {
        binder.registerCustomEditor(LocalDate.class, new RuDateEditor());
    }

    /**
     * Поле даты в шаблонах отдела — текстовое с маской дд.мм.гггг,
     * но браузерный виджет отдаёт yyyy-MM-dd; редактор принимает оба
     * формата, чтобы форма работала и с JavaScript, и без него.
     */
    static class RuDateEditor extends PropertyEditorSupport {

        @Override
        public void setAsText(String text) {
            if (text == null || text.isBlank()) {
                setValue(null);
                return;
            }

            String value = text.trim();

            try {
                setValue(value.contains("-")
                        ? LocalDate.parse(value)
                        : LocalDate.parse(value, RU_DATE));
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "Введите дату в формате дд.мм.гггг"
                );
            }
        }
    }
}
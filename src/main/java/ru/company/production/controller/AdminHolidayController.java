package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.HolidayForm;
import ru.company.production.dto.HolidayRowView;
import ru.company.production.entity.HolidayKind;
import ru.company.production.service.HolidayService;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Раздел администратора «Выходные и праздничные дни».
 *
 * Раньше нерабочие дни проставлял каждый руководитель цеха в своём
 * календаре, из-за чего календари цехов расходились, а один и тот же
 * праздник приходилось вносить несколько раз. Здесь день заводится один
 * раз и сразу отмечается у всех действующих сотрудников предприятия.
 */
@Controller
@RequestMapping("/admin/holidays")
@RequiredArgsConstructor
public class AdminHolidayController {

    private static final DateTimeFormatter MONTH_PARAM =
            DateTimeFormatter.ofPattern("yyyy-MM");

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final HolidayService holidayService;

    /*
     * То же правило разбора даты, что и в календаре цеха: поле отдаёт
     * yyyy-MM-dd, а при ручном вводе возможен дд.мм.гггг.
     */
    @InitBinder("holidayForm")
    public void bindHolidayForm(WebDataBinder binder) {
        binder.registerCustomEditor(
                LocalDate.class,
                new WorkshopCalendarController.RuDateEditor()
        );
    }

    @GetMapping({"", "/"})
    public String index(
            @RequestParam(required = false) String month,
            @ModelAttribute("holidayForm") HolidayForm holidayForm,
            Model model
    ) {
        fillModel(parseMonth(month), model);

        model.addAttribute("activePage", "holidays");

        return "admin/holidays";
    }

    /**
     * Добавление нерабочего дня: день справочника плюс отметки всем
     * действующим сотрудникам.
     */
    @PostMapping("/add")
    public String add(
            @ModelAttribute("holidayForm") HolidayForm holidayForm,
            RedirectAttributes redirectAttributes
    ) {
        YearMonth target = YearMonth.from(
                holidayForm.getHolidayDate() == null
                        ? LocalDate.now()
                        : holidayForm.getHolidayDate()
        );

        try {
            int marked = holidayService.add(
                    holidayForm.getHolidayDate(),
                    parseKind(holidayForm.getKind()),
                    holidayForm.getName(),
                    holidayForm.isReplaceExisting()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "День добавлен, отмечено " + marked
                            + plural(marked, " сотрудник", " сотрудника",
                            " сотрудников")
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

            redirectAttributes.addFlashAttribute("holidayForm", holidayForm);
        }

        return "redirect:/admin/holidays?month=" + target.format(MONTH_PARAM);
    }

    /**
     * Выходные выбранного месяца разом: субботы и воскресенья, которых ещё
     * нет в справочнике.
     */
    @PostMapping("/weekends")
    public String addWeekends(
            @RequestParam(required = false) String month,
            @RequestParam(required = false, defaultValue = "false")
            boolean replaceExisting,
            RedirectAttributes redirectAttributes
    ) {
        YearMonth target = parseMonth(month);

        try {
            int marked = holidayService.addMonthWeekends(
                    target,
                    replaceExisting
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Выходные добавлены: " + marked
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
        }

        return "redirect:/admin/holidays?month=" + target.format(MONTH_PARAM);
    }

    @PostMapping("/delete/{id}")
    public String delete(
            @PathVariable Long id,
            @RequestParam(required = false) String month,
            RedirectAttributes redirectAttributes
    ) {
        try {
            int removed = holidayService.remove(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "День удалён, снято " + removed
                            + plural(removed, " отметка", " отметки", " отметок")
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
        }

        return "redirect:/admin/holidays?month=" + parseMonth(month).format(
                MONTH_PARAM
        );
    }

    private void fillModel(YearMonth month, Model model) {
        Locale russian = Locale.forLanguageTag("ru");

        List<HolidayRowView> holidays = holidayService.findByMonth(month)
                .stream()
                .map(HolidayRowView::of)
                .toList();

        model.addAttribute("holidays", holidays);
        model.addAttribute("holidayKinds", HolidayKind.values());
        model.addAttribute("month", month);
        model.addAttribute(
                "monthLabel",
                month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, russian)
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
        model.addAttribute("today", LocalDate.now().format(RU_DATE));
        model.addAttribute(
                "totalCount",
                holidayService.count()
        );
    }

    private static YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now();
        }

        try {
            return YearMonth.parse(value.trim(), MONTH_PARAM);
        } catch (DateTimeParseException exception) {
            return YearMonth.now();
        }
    }

    private static HolidayKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return HolidayKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Выберите вид дня");
        }
    }

    private static String plural(
            int value,
            String one,
            String few,
            String many
    ) {
        int lastDigit = Math.abs(value) % 10;
        int lastTwo = Math.abs(value) % 100;

        if (lastTwo >= 11 && lastTwo <= 14) {
            return many;
        }

        if (lastDigit == 1) {
            return one;
        }

        if (lastDigit >= 2 && lastDigit <= 4) {
            return few;
        }

        return many;
    }
}

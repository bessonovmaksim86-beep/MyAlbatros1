package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.dto.StaffOperationRow;
import ru.company.production.dto.StaffProductivityRow;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopProductivityService;
import ru.company.production.service.WorkshopStaffService;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Статистика производительности сотрудников цеха.
 */
@Controller
@RequestMapping("/workshop/productivity")
@RequiredArgsConstructor
public class WorkshopProductivityController {

    private final WorkshopProductivityService productivityService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    @GetMapping({"", "/"})
    public String index(
            @RequestParam(value = "month", required = false)
                    @DateTimeFormat(pattern = "yyyy-MM") String month,
            @RequestParam(value = "from", required = false)
                    @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate fromParam,
            @RequestParam(value = "to", required = false)
                    @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate toParam,
            Authentication authentication,
            Model model
    ) {
        AppUser supervisor = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (supervisor == null) {
            return "redirect:/login";
        }

        /*
         * Определяем период: по умолчанию — текущий месяц.
         */
        LocalDate today = LocalDate.now();
        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        String selectedMonth = null;

        if (month != null && !month.isBlank()) {
            LocalDate selected = LocalDate.parse(month + "-01");
            from = selected.withDayOfMonth(1);
            to = selected.withDayOfMonth(selected.lengthOfMonth());
            selectedMonth = month;
        } else if (fromParam != null && toParam != null) {
            from = fromParam;
            to = toParam;
        }

        model.addAttribute("monthOptions", monthOptions(today));
        model.addAttribute("selectedMonth", selectedMonth);

        /*
         * Границы подразделения.
         */
        List<Long> unitIds = staffService.unitScope(supervisor);
        if (unitIds.isEmpty()) {
            model.addAttribute("productivityRows", List.of());
            model.addAttribute("operationRows", List.of());
            model.addAttribute("from", from);
            model.addAttribute("to", to);
            return "workshop/productivity/index";
        }

        List<StaffProductivityRow> rows =
                productivityService.findProductivity(unitIds, from, to);

        List<StaffOperationRow> opRows =
                productivityService.findOperations(unitIds, from, to);

        model.addAttribute("productivityRows", rows);
        model.addAttribute("operationRows", opRows);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("activePage", "productivity");

        return "workshop/productivity/index";
    }

    /**
     * Список последних 12 месяцев для фильтра.
     * Названия формируются на русском, чтобы не зависеть от локали JVM.
     */
    private List<MonthOption> monthOptions(LocalDate current) {
        List<MonthOption> options = new ArrayList<>();
        for (int back = 0; back < 12; back++) {
            LocalDate monthStart = current.withDayOfMonth(1).minusMonths(back);
            String label = monthStart.getMonth()
                    .getDisplayName(TextStyle.FULL_STANDALONE, new Locale("ru"));
            options.add(new MonthOption(
                    String.format("%04d-%02d",
                            monthStart.getYear(), monthStart.getMonthValue()),
                    Character.toUpperCase(label.charAt(0)) + label.substring(1)));
        }
        return options;
    }

    /**
     * Пункт выпадающего списка месяцев.
     */
    public record MonthOption(String value, String label) {
    }
}

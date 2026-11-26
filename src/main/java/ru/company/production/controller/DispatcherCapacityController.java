package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.service.CapacityAnalysisService;

/**
 * Анализ производственных мощностей.
 *
 * Страница диспетчера: загрузка цехов и отделов по неделям,
 * свободные мощности, операции без назначения и рекомендации.
 */
@Controller
@RequestMapping("/dispatcher/capacity")
@RequiredArgsConstructor
public class DispatcherCapacityController {

    private final CapacityAnalysisService capacityAnalysisService;

    @GetMapping
    public String capacity(
            @RequestParam(name = "weeks", defaultValue = "8") int weeks,
            Model model
    ) {
        CapacityAnalysisService.CapacityAnalysisResult result =
                capacityAnalysisService.analyze(weeks);

        model.addAttribute("activePage", "capacity");
        model.addAttribute("kpi", result.kpi());
        model.addAttribute("units", result.units());
        model.addAttribute("unassigned", result.unassigned());
        model.addAttribute("recommendations", result.recommendations());
        model.addAttribute("weeks", result.weeks());
        model.addAttribute("dateFrom", result.dateFrom());
        model.addAttribute("dateTo", result.dateTo());
        model.addAttribute("periodOptions", new int[]{4, 8, 12, 16});

        /*
         * Шапка тепловой карты: у всех подразделений недели одинаковые,
         * поэтому достаточно первой строки. Пустой список, если
         * подразделений нет вовсе.
         */
        model.addAttribute("weekHeaders", result.units().isEmpty()
                ? java.util.List.of()
                : result.units().get(0).weeks());

        return "dispatcher/capacity/index";
    }
}

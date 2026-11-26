package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.dto.ActualPerformanceResult;
import ru.company.production.service.ActualPerformanceService;

/**
 * Фактическая производительность — страница диспетчера.
 */
@Controller
@RequestMapping("/dispatcher/capacity/actual")
@RequiredArgsConstructor
public class DispatcherActualPerformanceController {

    private static final int DEFAULT_WEEKS = 8;

    private final ActualPerformanceService actualPerformanceService;

    @GetMapping
    public String actualPerformance(
            @RequestParam(name = "weeks", defaultValue = "8") int weeks,
            Model model
    ) {
        ActualPerformanceResult result =
                actualPerformanceService.analyze(weeks);

        model.addAttribute("activePage", "actualPerformance");
        model.addAttribute("kpi", result.kpi());
        model.addAttribute("unitStats", result.unitStats());
        model.addAttribute("weeklyTrend", result.weeklyTrend());
        model.addAttribute("productionOutput", result.productionOutput());
        model.addAttribute("executorRanking", result.executorRanking());
        model.addAttribute("lateOperations", result.lateOperations());
        model.addAttribute("weeks", result.weeks());
        model.addAttribute("dateFrom", result.dateFrom());
        model.addAttribute("dateTo", result.dateTo());
        model.addAttribute("periodOptions", new int[]{4, 8, 12});

        return "dispatcher/actual-performance/index";
    }
}

package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.dto.CapacityForecastForm;
import ru.company.production.dto.CapacityForecastResult;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.service.CapacityForecastService;

import java.time.LocalDate;
import java.util.List;

/**
 * Плановый расчёт выполнения — калькулятор возможностей цехов.
 */
@Controller
@RequestMapping("/dispatcher/capacity/forecast")
@RequiredArgsConstructor
public class DispatcherForecastController {

    private final CapacityForecastService forecastService;
    private final ProductClassifierRepository classifierRepository;

    @GetMapping
    public String forecast(Model model) {
        List<ProductClassifier> classifiers = classifierRepository
                .findAllByOrderByCodeAsc();

        model.addAttribute("activePage", "capacityForecast");
        model.addAttribute("classifiers", classifiers);
        model.addAttribute("form", new CapacityForecastForm(null, 1, null));
        model.addAttribute("result", null);
        model.addAttribute("ignoreLoad", false);
        model.addAttribute("today", LocalDate.now());

        return "dispatcher/capacity-forecast/index";
    }

    @PostMapping
    public String calculate(
            @RequestParam(name = "classifierId") Long classifierId,
            @RequestParam(name = "quantity", defaultValue = "1") int quantity,
            @RequestParam(name = "startDate",
                    required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(name = "ignoreLoad", defaultValue = "false")
                    boolean ignoreLoad,
            Model model
    ) {
        List<ProductClassifier> classifiers = classifierRepository
                .findAllByOrderByCodeAsc();

        CapacityForecastForm form = new CapacityForecastForm(
                classifierId, Math.max(1, quantity),
                startDate != null ? startDate : LocalDate.now());

        CapacityForecastResult result =
                forecastService.calculate(form, ignoreLoad);

        model.addAttribute("activePage", "capacityForecast");
        model.addAttribute("classifiers", classifiers);
        model.addAttribute("form", form);
        model.addAttribute("result", result);
        model.addAttribute("ignoreLoad", ignoreLoad);
        model.addAttribute("today", LocalDate.now());

        return "dispatcher/capacity-forecast/index";
    }
}

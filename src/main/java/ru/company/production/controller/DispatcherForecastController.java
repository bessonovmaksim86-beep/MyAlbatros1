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
import ru.company.production.dto.ForecastAggregated;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.service.CapacityForecastService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Плановый расчёт выполнения — калькулятор возможностей цехов.
 *
 * Диспетчер может выбрать несколько изделий за один расчёт:
 * каждая позиция считается отдельно, а итоговая сводка
 * показывает общую дату завершения и лимитирующую стадию.
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
        model.addAttribute("items", List.of(
                new CapacityForecastForm(null, 1, null)));
        model.addAttribute("results", List.of());
        model.addAttribute("aggregated", null);
        model.addAttribute("ignoreLoad", false);
        model.addAttribute("startDate", LocalDate.now());
        model.addAttribute("today", LocalDate.now());

        return "dispatcher/capacity-forecast/index";
    }

    @PostMapping
    public String calculate(
            @RequestParam(name = "classifierIds", required = false)
                    List<Long> classifierIds,
            @RequestParam(name = "quantities", required = false)
                    List<Integer> quantities,
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

        LocalDate effectiveStart = startDate != null
                ? startDate
                : LocalDate.now();

        /*
         * Собираем непустые позиции формы. Количество по умолчанию — 1.
         */
        List<CapacityForecastForm> items = new ArrayList<>();
        List<CapacityForecastResult> results = new ArrayList<>();

        if (classifierIds != null) {
            for (int i = 0; i < classifierIds.size(); i++) {
                Long classifierId = classifierIds.get(i);

                if (classifierId == null) {
                    continue;
                }

                int quantity = quantities != null
                        && i < quantities.size()
                        && quantities.get(i) != null
                        ? Math.max(1, quantities.get(i))
                        : 1;

                CapacityForecastForm item = new CapacityForecastForm(
                        classifierId, quantity, effectiveStart);

                items.add(item);
                results.add(forecastService.calculate(item, ignoreLoad));
            }
        }

        /*
         * Агрегат: общая дата завершения — максимум по позициям,
         * лимитирующая — та операция, что закончилась последней.
         * При равных датах предпочитаем позицию с реальным
         * маршрутом, иначе «пустая» позиция затирала бы стадию.
         */
        ForecastAggregated aggregated = null;

        if (!results.isEmpty()) {
            int totalQty = 0;
            BigDecimal totalLabor = BigDecimal.ZERO;
            LocalDate finish = effectiveStart;
            boolean feasible = true;
            CapacityForecastResult limiting = null;

            for (CapacityForecastResult result : results) {
                totalQty += result.summary().quantity();
                totalLabor = totalLabor.add(
                        result.summary().totalLaborHours());

                if (!result.feasible()) {
                    feasible = false;
                }

                LocalDate resultFinish = result.summary().finishDate();

                if (resultFinish == null) {
                    continue;
                }

                if (resultFinish.isAfter(finish)) {
                    finish = resultFinish;
                }

                if (limiting == null
                        || resultFinish.isAfter(
                                limiting.summary().finishDate())
                        || (resultFinish.isEqual(
                                limiting.summary().finishDate())
                                && hasRealLimiting(result)
                                && !hasRealLimiting(limiting))) {
                    limiting = result;
                }
            }

            int totalDays = (int) Math.max(
                    0, ChronoUnit.DAYS.between(effectiveStart, finish));

            aggregated = new ForecastAggregated(
                    totalQty,
                    finish,
                    totalLabor,
                    limiting != null
                            ? limiting.summary().classifierName()
                            : "—",
                    limiting != null
                            ? limiting.summary().limitingOperation()
                            : "—",
                    limiting != null
                            ? limiting.summary().limitingHours()
                            : BigDecimal.ZERO,
                    totalDays,
                    feasible);
        }

        if (items.isEmpty()) {
            items = List.of(
                    new CapacityForecastForm(null, 1, effectiveStart));
        }

        model.addAttribute("activePage", "capacityForecast");
        model.addAttribute("classifiers", classifiers);
        model.addAttribute("items", items);
        model.addAttribute("results", results);
        model.addAttribute("aggregated", aggregated);
        model.addAttribute("ignoreLoad", ignoreLoad);
        model.addAttribute("startDate", effectiveStart);
        model.addAttribute("today", LocalDate.now());

        return "dispatcher/capacity-forecast/index";
    }

    /*
     * Есть ли у позиции настоящая лимитирующая операция.
     * У позиции без техпроцесса или маршрута она равна «—»,
     * и такая позиция не должна перекрывать реальную стадию
     * при совпадении дат завершения.
     */
    private static boolean hasRealLimiting(
            CapacityForecastResult result
    ) {
        String operation = result.summary().limitingOperation();

        return operation != null && !"—".equals(operation);
    }
}

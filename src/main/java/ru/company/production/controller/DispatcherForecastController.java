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
import ru.company.production.entity.OrderPriority;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.repository.OrderPriorityRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.service.CapacityForecastService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Плановый расчёт выполнения — калькулятор возможностей цехов.
 *
 * Диспетчер может выбрать несколько изделий за один расчёт:
 * каждая позиция считается отдельно, а итоговая сводка
 * показывает общую дату завершения и лимитирующую стадию.
 * Приоритет общий на весь расчёт — он задаёт правила запуска,
 * переходов и допустимой загрузки цехов.
 */
@Controller
@RequestMapping("/dispatcher/capacity/forecast")
@RequiredArgsConstructor
public class DispatcherForecastController {

    private final CapacityForecastService forecastService;
    private final ProductClassifierRepository classifierRepository;
    private final OrderPriorityRepository priorityRepository;

    @GetMapping
    public String forecast(Model model) {
        List<ProductClassifier> classifiers = classifierRepository
                .findAllByOrderByCodeAsc();

        List<OrderPriority> priorities = priorityRepository
                .findByActiveTrueOrderByIdAsc();

        model.addAttribute("activePage", "capacityForecast");
        model.addAttribute("classifiers", classifiers);
        model.addAttribute("priorities", priorities);
        model.addAttribute("items", List.of(
                new CapacityForecastForm(null, 1, null, defaultPriorityId(
                        priorities))));
        model.addAttribute("results", List.of());
        model.addAttribute("aggregated", null);
        model.addAttribute("ignoreLoad", false);
        model.addAttribute("priorityId", defaultPriorityId(priorities));
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
            @RequestParam(name = "priorityId", required = false)
                    Long priorityId,
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

        List<OrderPriority> priorities = priorityRepository
                .findByActiveTrueOrderByIdAsc();

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
                        classifierId, quantity, effectiveStart, priorityId);

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
            LocalDate finish = null;
            boolean feasible = true;
            int missingCapacity = 0;
            int totalDays = 0;
            CapacityForecastResult limiting = null;
            String priorityName = "—";
            String priorityBadge = null;

            /*
             * Приоритет показываем из справочника по выбранному id:
             * позиция без техпроцесса не несёт сведений о приоритете,
             * и брать их из результатов было бы ненадёжно.
             */
            OrderPriority selectedPriority = findPriority(
                    priorities, priorityId);

            if (selectedPriority != null) {
                priorityName = selectedPriority.getName();
                priorityBadge = selectedPriority.getBadgeClass();
            }

            for (CapacityForecastResult result : results) {
                totalQty += result.summary().quantity();
                totalLabor = totalLabor.add(
                        result.summary().totalLaborHours());

                if (!result.feasible()) {
                    feasible = false;
                }

                missingCapacity +=
                        result.summary().missingCapacityOperations();

                LocalDate resultFinish = result.summary().finishDate();

                if (resultFinish == null) {
                    continue;
                }

                /*
                 * Срок изготовления — в рабочих днях, как в карточке
                 * позиции: календарная разница дала бы «0 дн.» при
                 * старте и финише в один день и врала бы на выходных.
                 */
                totalDays = Math.max(
                        totalDays,
                        result.summary().totalWorkingDays());

                if (finish == null || resultFinish.isAfter(finish)) {
                    finish = resultFinish;
                }

                if (limiting == null
                        || limiting.summary().finishDate() == null
                        || resultFinish.isAfter(
                                limiting.summary().finishDate())
                        || (resultFinish.isEqual(
                                limiting.summary().finishDate())
                                && hasRealLimiting(result)
                                && !hasRealLimiting(limiting))) {
                    limiting = result;
                }
            }

            /*
             * Срок всего заказа недостоверен, если хотя бы у одной
             * позиции нет мощности: дата «по остальным» выдала бы
             * частичный расчёт за общий.
             */
            boolean reliable = missingCapacity == 0;
            LocalDate aggregateFinish = reliable ? finish : null;

            if (aggregateFinish == null) {
                feasible = false;
                totalDays = 0;
            }

            aggregated = new ForecastAggregated(
                    totalQty,
                    aggregateFinish,
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
                    feasible,
                    priorityName,
                    priorityBadge,
                    missingCapacity
            );
        }

        if (items.isEmpty()) {
            items = List.of(
                    new CapacityForecastForm(null, 1, effectiveStart,
                            defaultPriorityId(priorities)));
        }

        model.addAttribute("activePage", "capacityForecast");
        model.addAttribute("classifiers", classifiers);
        model.addAttribute("priorities", priorities);
        model.addAttribute("items", items);
        model.addAttribute("results", results);
        model.addAttribute("aggregated", aggregated);
        model.addAttribute("ignoreLoad", ignoreLoad);
        model.addAttribute("priorityId", priorityId);
        model.addAttribute("startDate", effectiveStart);
        model.addAttribute("today", LocalDate.now());

        return "dispatcher/capacity-forecast/index";
    }

    /*
     * Приоритет по умолчанию для формы: «обычный», иначе первый
     * действующий из справочника.
     */
    private static Long defaultPriorityId(List<OrderPriority> priorities) {
        for (OrderPriority priority : priorities) {
            if ("ORDINARY".equals(priority.getCode())) {
                return priority.getId();
            }
        }

        return priorities.isEmpty()
                ? null
                : priorities.get(0).getId();
    }

    /*
     * Приоритет по id из формы; если id не передан — «обычный»
     * или первый действующий, ровно как в форме по умолчанию.
     */
    private static OrderPriority findPriority(
            List<OrderPriority> priorities,
            Long priorityId
    ) {
        Long effectiveId = priorityId != null
                ? priorityId
                : defaultPriorityId(priorities);

        for (OrderPriority priority : priorities) {
            if (priority.getId().equals(effectiveId)) {
                return priority;
            }
        }

        return null;
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

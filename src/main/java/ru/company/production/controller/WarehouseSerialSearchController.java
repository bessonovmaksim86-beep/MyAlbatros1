package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.dto.WarehouseSerialSearchFilter;
import ru.company.production.dto.WarehouseSerialSearchRow;
import ru.company.production.service.WarehouseSerialSearchService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Поиск серийного номера повреждённой этикетки.
 *
 * Раздел отвечает на вопрос «какой это номер?», когда QR-этикетка
 * на ячейке или подсборке повреждена и прочесть номер невозможно.
 * Кладовщик или технолог вводит известные наводки — классификатор,
 * операцию, на которой обнаружена позиция, перечень пройденных
 * операций, статус, период — и получает перечень предполагаемых
 * серийных номеров.
 *
 * Форма отдаёт данные GET'ом: ссылку на «ячейки 5127 на операции
 * сборки» передают коллеге, и выбор не должен сбрасываться от
 * перехода по прочим пунктам меню. Подписи модуля кладёт
 * {@link WarehouseViewAdvice}, к которому контроллер подключён.
 */
@Controller
@RequestMapping("/warehouse/serial-search")
@RequiredArgsConstructor
public class WarehouseSerialSearchController {

    private final WarehouseSerialSearchService searchService;

    @GetMapping({"", "/"})
    public String search(
            @RequestParam(name = "classifierId", required = false) Long classifierId,
            @RequestParam(name = "productName", required = false) String productName,
            @RequestParam(name = "numberHint", required = false) String numberHint,
            @RequestParam(name = "operationFoundId", required = false) Long operationFoundId,
            @RequestParam(name = "operationFoundName", required = false) String operationFoundName,
            @RequestParam(name = "completedOperationIds", required = false) List<Long> completedOperationIds,
            @RequestParam(name = "completedOperationNames", required = false) String completedOperationNames,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "productType", required = false) String productType,
            @RequestParam(name = "inputControlType", required = false) String inputControlType,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo,
            @RequestParam(name = "supplierName", required = false) String supplierName,
            @RequestParam(name = "incomingOrderNumber", required = false) String incomingOrderNumber,
            @RequestParam(name = "znpNumber", required = false) String znpNumber,
            @RequestParam(name = "productionOrderNumber", required = false) String productionOrderNumber,
            @RequestParam(name = "unitName", required = false) String unitName,
            @RequestParam(name = "excludedIds", required = false, defaultValue = "") String excludedIds,
            Model model
    ) {

        WarehouseSerialSearchFilter.OperationFilter operationFound =
                operationFoundId != null
                        ? new WarehouseSerialSearchFilter.OperationFilter(
                                operationFoundId,
                                blankToNull(operationFoundName))
                        : null;

        List<WarehouseSerialSearchFilter.OperationFilter> completedOps =
                buildCompletedOperations(completedOperationIds);

        WarehouseSerialSearchFilter filter = new WarehouseSerialSearchFilter(
                classifierId,
                blankToNull(productName),
                blankToNull(numberHint),
                operationFound,
                completedOps,
                parseStatus(status),
                parseProductType(productType),
                parseInputControlType(inputControlType),
                parseLocalDate(dateFrom),
                parseLocalDate(dateTo),
                blankToNull(supplierName),
                blankToNull(incomingOrderNumber),
                blankToNull(znpNumber),
                blankToNull(productionOrderNumber),
                blankToNull(unitName)
        );

        List<WarehouseSerialSearchRow> rows = searchService.search(filter);

        /*
         * Исключаем уже идентифицированные номера.
         *
         * Поле принимает ПОЛНЫЕ номера (код классификатора + серия) —
         * именно их кладовщик видит на повреждённой этикетке и в
         * перечне результата; внутренний идентификатор реестра ему
         * неизвестен. Сверка регистронезависимая и по обрезанной
         * строке: «512700011 » с пробелом — тот же номер.
         */
        java.util.Set<String> excludedNumbers = parseExcludedNumbers(excludedIds);

        if (!excludedNumbers.isEmpty()) {
            rows = rows.stream()
                    .filter(r -> !excludedNumbers.contains(
                            r.getFullNumber().trim().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }

        model.addAttribute("activePage", "serial-search");
        model.addAttribute("filter", filter);
        model.addAttribute("rows", rows);
        model.addAttribute("excludedIds", excludedIds);
        model.addAttribute("filteredCount", rows.size());

        addFilterOptions(model);

        return "warehouse/serial-search/index";
    }

    @GetMapping(params = "reset")
    public String reset(Model model) {
        model.addAttribute("activePage", "serial-search");
        model.addAttribute("filter", WarehouseSerialSearchFilter.empty());
        model.addAttribute("rows", List.<WarehouseSerialSearchRow>of());
        model.addAttribute("excludedIds", "");
        model.addAttribute("filteredCount", 0);
        addFilterOptions(model);
        return "warehouse/serial-search/index";
    }

    private void addFilterOptions(Model model) {
        List<WarehouseSerialSearchService.OperationOption> operations =
                searchService.findAllOperations();
        List<WarehouseSerialSearchService.ClassifierOption> classifiers =
                searchService.findAllClassifiers();

        model.addAttribute("operations", operations);
        model.addAttribute("productOptions", classifiers);

        /*
         * Перечисления передают в модель, а не читают из шаблона:
         * диалект java8time в проект не подключён, и {@code #enums}
         * здесь не поддержан — тот же приём, что у реестра наличия.
         */
        model.addAttribute("statuses",
                ru.company.production.entity.ProductSerialStatus.values());
        model.addAttribute("productTypes",
                ru.company.production.entity.ProductType.values());
        model.addAttribute("inputControlTypes",
                ru.company.production.entity.InputControlType.values());
    }

    private List<WarehouseSerialSearchFilter.OperationFilter>
    buildCompletedOperations(List<Long> ids) {

        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return ids.stream()
                .filter(id -> id != null && id > 0)
                .map(WarehouseSerialSearchFilter.OperationFilter::ofId)
                .toList();
    }

    private ru.company.production.entity.ProductSerialStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ru.company.production.entity.ProductSerialStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ru.company.production.entity.ProductType parseProductType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ru.company.production.entity.ProductType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ru.company.production.entity.InputControlType parseInputControlType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ru.company.production.entity.InputControlType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static java.time.LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static java.util.Set<String> parseExcludedNumbers(String value) {
        if (value == null || value.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

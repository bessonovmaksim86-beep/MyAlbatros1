package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.dto.WarehouseStockFilter;
import ru.company.production.dto.WarehouseStockRow;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.service.WarehouseStockService;

import java.time.LocalDate;
import java.util.List;

/**
 * Наличие на складе полуфабрикатов: реестр ячеек и подсборок.
 *
 * Раздел отвечает на вопрос, на который прочая разметка склада ответа
 * не даёт: что сейчас лежит на полках и в каких заказах уже занято.
 * Приход, приёмка и комплектование показывают свою стадию маршрута, и
 * кладовщику приходилось обходить их все, чтобы сосчитать остаток.
 *
 * Адрес живёт внутри {@code /warehouse/**} намеренно: право на него
 * даёт уже настроенное правило модуля склада, и отдельного правила в
 * конфигурации безопасности раздел не требует. Подписи модуля кладёт
 * {@link WarehouseViewAdvice}, к которому контроллер подключён.
 *
 * Отбор и сортировка живут в адресной строке, а не в сессии: ссылку на
 * «подсборки контрагента за неделю» передают коллеге, и выбор не должен
 * сбрасываться от перехода по прочим пунктам меню. Форма отдаёт GET'ом,
 * поэтому значения возвращаются в модель и экран не «забывает» отбор
 * после применения (тот же приём, что у перечня изолятора брака).
 */
@Controller
@RequestMapping("/warehouse/stock")
@RequiredArgsConstructor
public class WarehouseStockController {

    private final WarehouseStockService stockService;

    @GetMapping({"", "/"})
    public String index(
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "classifierId", required = false) Long classifierId,
            @RequestParam(name = "supplier", required = false) String supplier,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo,
            @RequestParam(name = "sort", required = false) String sort,
            Model model
    ) {

        WarehouseStockFilter filter = new WarehouseStockFilter(
                parseKind(kind),
                classifierId,
                blankToNull(supplier),
                parseDate(dateFrom),
                parseDate(dateTo),
                WarehouseStockFilter.Sorting.ofNullable(sort)
        );

        model.addAttribute("activePage", "stock");

        List<WarehouseStockRow> rows = stockService.findRows(filter);

        model.addAttribute("rows", rows);
        model.addAttribute("summary", stockService.summarize(rows));

        model.addAttribute("filter", filter);
        model.addAttribute("filterKind", filter.kind());
        model.addAttribute("filterClassifierId", filter.classifierId());
        model.addAttribute("filterSupplier", filter.supplierKey());
        model.addAttribute("filterDateFrom", filter.dateFromParam());
        model.addAttribute("filterDateTo", filter.dateToParam());
        model.addAttribute("filterSorting", filter.sortingOrDefault());

        model.addAttribute("kinds", QuarantineCellKind.values());
        model.addAttribute("sortings", WarehouseStockFilter.Sorting.values());

        addFilterOptions(model);

        return "warehouse/stock/index";
    }

    /**
     * Опции выпадающих списков: изделия и контрагенты самого склада.
     *
     * Их берут из реестра наличия, а не из справочников целиком —
     * иначе список изделий на сотни наименований и перечень поставщиков
     * всех приходов предлагали бы фильтровать по тому, чего на складе
     * нет.
     */
    private void addFilterOptions(Model model) {

        List<WarehouseStockService.StockOption> products =
                stockService.findProductOptions();

        model.addAttribute("productOptions", products);
        model.addAttribute(
                "supplierOptions",
                stockService.findSupplierOptions()
        );
    }

    /** Вид позиции: неизвестное значение означает «не выбран», а не ошибку. */
    private static QuarantineCellKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return QuarantineCellKind.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Дата поля ввода: неразобранное значение снимает условие, а не роняет страницу. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

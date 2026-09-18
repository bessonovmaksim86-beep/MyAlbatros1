package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.TechProcessForm;
import ru.company.production.dto.TypeCardView;
import ru.company.production.dto.TypeCards;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.TechProcessListItemView;
import ru.company.production.service.TechProcessService;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/technology/techprocess")
@RequiredArgsConstructor
public class TechProcessController {

    private final TechProcessService techProcessService;

    /**
     * Порядок типов изделий на главной странице техпроцессов.
     */
    private static final ProductType[] TYPE_ORDER = {
            ProductType.CELL,
            ProductType.SENSOR,
            ProductType.DEVICE,
            ProductType.SYSTEM
    };

    /**
     * Список техпроцессов.
     */
    @GetMapping
    public String index(
            @RequestParam(
                    name = "type",
                    required = false
            )
            String type,
            Model model
    ) {
        List<TechProcessListItemView> all =
                techProcessService.findAllWithClassifiers();

        ProductType activeType = parseType(type);

        model.addAttribute("activePage", "techprocess");

        model.addAttribute(
                "techProcesses",
                activeType == null
                        ? all
                        : all.stream()
                            .filter(row ->
                                    row.getProductType() == activeType)
                            .toList()
        );

        model.addAttribute(
                "totalCount",
                all.size()
        );

        model.addAttribute(
                "activeType",
                activeType == null ? null : activeType.name()
        );

        model.addAttribute(
                "activeTypeName",
                activeType == null
                        ? null
                        : activeType.getDisplayName()
        );

        model.addAttribute(
                "techProcessCards",
                buildTechProcessCards(all)
        );

        return "technology/techprocess/index";
    }

    private ProductType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }

        try {
            return ProductType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Карточки техпроцессов по типам изделий.
     */
    private List<TypeCardView> buildTechProcessCards(
            List<TechProcessListItemView> rows
    ) {
        List<TypeCardView> cards = new ArrayList<>();

        for (int index = 0; index < TYPE_ORDER.length; index++) {
            ProductType type = TYPE_ORDER[index];

            long count = rows.stream()
                    .filter(row -> row.getProductType() == type)
                    .count();

            cards.add(new TypeCardView(
                    type.name(),
                    type.getDisplayName(),
                    count,
                    TypeCards.icon(type.getDisplayName()),
                    TypeCards.modifier(index)
            ));
        }

        return cards;
    }

    /**
     * Создание начинается с выбора изделия,
     * поэтому сразу переводим пользователя на страницу выбора.
     */
    @GetMapping("/create")
    public String createRedirect() {
        return "redirect:/technology/techprocess/create/select";
    }

    /**
     * Выбор изделия классификатора для нового техпроцесса:
     * вид изделия, класс датчика и само изделие.
     */
    @GetMapping("/create/select")
    public String createSelectionPage(Model model) {
        fillSelectionModel(model);

        return "technology/techprocess/create-select";
    }

    /**
     * Форма нового техпроцесса для выбранного изделия.
     * Код выдаётся автоматически.
     */
    @GetMapping("/create/new")
    public String createPage(
            @RequestParam(required = false) Long classifierId,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (classifierId == null) {
            return "redirect:/technology/techprocess/create/select";
        }

        if (!model.containsAttribute("techProcessForm")) {
            TechProcessForm form = new TechProcessForm();
            form.setProductClassifierId(classifierId);
            form.setCode(techProcessService.nextCode());

            model.addAttribute("techProcessForm", form);
        }

        fillCommonModel(model);
        fillClassifierSummary(model, classifierId);

        return "technology/techprocess/create";
    }

    /**
     * Сохранение нового техпроцесса.
     */
    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("techProcessForm")
            TechProcessForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (form.getCode() == null || form.getCode().isBlank()) {
            form.setCode(techProcessService.nextCode());
        }

        if (!errors.hasErrors()) {
            try {
                techProcessService.create(form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException |
                    IllegalStateException exception
            ) {
                errors.reject(
                        "techprocess.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillCommonModel(model);
            fillClassifierSummary(
                    model,
                    form.getProductClassifierId()
            );

            return "technology/techprocess/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Техпроцесс успешно создан"
        );

        return "redirect:/technology/techprocess";
    }

    /**
     * Страница выбора техпроцесса для изменения.
     */
    @GetMapping("/update")
    public String updateSelectionPage(Model model) {
        model.addAttribute("activePage", "techprocess");
        model.addAttribute(
                "techProcesses",
                techProcessService.findAll()
        );

        return "technology/techprocess/update-select";
    }

    /**
     * Страница изменения выбранного техпроцесса.
     */
    @GetMapping("/update/{id}")
    public String updatePage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            TechProcessForm form =
                    techProcessService.getForm(id);

            model.addAttribute("techProcessForm", form);

            fillCommonModel(model);
            fillClassifierSummary(
                    model,
                    form.getProductClassifierId()
            );

            return "technology/techprocess/update";
        } catch (
                EntityNotFoundException |
                IllegalArgumentException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/techprocess/update";
        }
    }

    /**
     * Сохранение изменений техпроцесса.
     */
    @PostMapping("/update/{id}")
    public String update(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("techProcessForm")
            TechProcessForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        form.setId(id);

        if (!errors.hasErrors()) {
            try {
                techProcessService.update(id, form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException |
                    IllegalStateException exception
            ) {
                errors.reject(
                        "techprocess.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillCommonModel(model);
            fillClassifierSummary(
                    model,
                    form.getProductClassifierId()
            );

            return "technology/techprocess/update";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Техпроцесс успешно изменён"
        );

        return "redirect:/technology/techprocess";
    }

    /**
     * Страница выбора техпроцесса для удаления.
     */
    @GetMapping("/delete")
    public String deleteSelectionPage(Model model) {
        model.addAttribute("activePage", "techprocess");
        model.addAttribute(
                "techProcesses",
                techProcessService.findAll()
        );

        return "technology/techprocess/delete-select";
    }

    /**
     * Страница подтверждения удаления.
     */
    @GetMapping("/delete/{id}")
    public String deletePage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "techProcess",
                    techProcessService.getDetailed(id)
            );

            model.addAttribute("activePage", "techprocess");

            return "technology/techprocess/delete";
        } catch (
                EntityNotFoundException |
                IllegalArgumentException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/techprocess/delete";
        }
    }

    /**
     * Удаление техпроцесса.
     */
    @PostMapping("/delete/{id}")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            techProcessService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Техпроцесс успешно удалён"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/technology/techprocess";
    }

    /**
     * Визуализация маршрута техпроцесса (граф зависимостей).
     */
    @GetMapping("/{id}/view")
    public String view(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "graph",
                    techProcessService.buildGraph(id)
            );

            model.addAttribute("activePage", "techprocess");

            return "technology/techprocess/view";
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/techprocess";
        }
    }

    /**
     * Страница применения техпроцесса к другому изделию.
     * Маршрут не копируется: изделие добавляется к существующему
     * техпроцессу.
     */
    @GetMapping("/{id}/copy")
    public String applyPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "source",
                    techProcessService.getDetailed(id)
            );

            model.addAttribute(
                    "applied",
                    techProcessService.findAppliedOptions(id)
            );

            fillSelectionModel(model);

            return "technology/techprocess/copy";
        } catch (
                EntityNotFoundException |
                IllegalArgumentException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/techprocess";
        }
    }

    /**
     * Применение маршрута к выбранному изделию.
     */
    @PostMapping("/{id}/copy")
    public String apply(
            @PathVariable Long id,
            @RequestParam Long targetClassifierId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            techProcessService.applyToClassifier(
                    id,
                    targetClassifierId
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Техпроцесс применён к выбранному изделию"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/techprocess/" + id + "/copy";
        }

        return "redirect:/technology/techprocess";
    }

    /**
     * Общие данные для страниц создания и изменения.
     */
    private void fillCommonModel(Model model) {
        model.addAttribute("activePage", "techprocess");

        model.addAttribute(
                "operations",
                techProcessService.findActiveOperations()
        );

        model.addAttribute(
                "classifiers",
                techProcessService.findClassifiers()
        );

        model.addAttribute(
                "workPlaces",
                techProcessService.findActiveWorkPlaces()
        );

        model.addAttribute(
                "classifierOptions",
                techProcessService.findClassifierOptions()
        );

        model.addAttribute(
                "productTypes",
                ProductType.values()
        );
    }

    /**
     * Данные для каскадного выбора изделия.
     */
    private void fillSelectionModel(Model model) {
        model.addAttribute("activePage", "techprocess");

        model.addAttribute(
                "classifierOptions",
                techProcessService.findClassifierOptions()
        );

        model.addAttribute(
                "productTypes",
                ProductType.values()
        );
    }

    /**
     * Краткие данные выбранного изделия для шапки формы.
     */
    private void fillClassifierSummary(Model model, Long classifierId) {
        if (classifierId == null) {
            return;
        }

        model.addAttribute(
                "selectedClassifier",
                techProcessService.findClassifierOptions().stream()
                        .filter(option ->
                                classifierId.equals(option.getId()))
                        .findFirst()
                        .orElse(null)
        );
    }
}
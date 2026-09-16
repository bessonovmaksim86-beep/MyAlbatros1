package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.ClassifierForm;
import ru.company.production.dto.TypeCardView;
import ru.company.production.dto.TypeCards;
import ru.company.production.entity.InclusionMode;
import ru.company.production.entity.MeasurementUnit;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.service.ProductClassifierService;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/technology/classifier")
@RequiredArgsConstructor
public class TechnologyClassifierController {

    private final ProductClassifierService classifierService;

    /**
     * Порядок типов на главной странице классификатора.
     */
    private static final ProductType[] TYPE_ORDER = {
            ProductType.CELL,
            ProductType.SENSOR,
            ProductType.DEVICE,
            ProductType.SYSTEM
    };

    @GetMapping
    public String classifierPage(
            @RequestParam(
                    name = "type",
                    required = false
            )
            String type,
            Model model
    ) {
        fillCommonModel(model, null);

        List<ProductClassifier> all = classifierService.findAll();
        ProductType activeType = parseType(type);

        model.addAttribute(
                "classifiers",
                activeType == null
                        ? all
                        : all.stream()
                            .filter(item ->
                                    item.getProductType() == activeType)
                            .toList()
        );

        model.addAttribute(
                "activeType",
                activeType == null ? null : activeType.name()
        );
        model.addAttribute("totalCount", all.size());

        fillClassifierCards(model, all);

        return "technology/classifier/index";
    }

    /**
     * Разбирает параметр типа из адресной строки.
     * Некорректное значение трактуется как «показать все».
     */
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
     * Строит карточки-фильтры по типам изделий.
     */
    private void fillClassifierCards(
            Model model,
            List<ProductClassifier> all
    ) {
        List<TypeCardView> cards = new ArrayList<>();

        for (int index = 0; index < TYPE_ORDER.length; index++) {
            ProductType type = TYPE_ORDER[index];
            long count = all.stream()
                    .filter(item -> item.getProductType() == type)
                    .count();

            cards.add(new TypeCardView(
                    type.name(),
                    type.getDisplayName(),
                    count,
                    TypeCards.icon(type.getDisplayName()),
                    TypeCards.modifier(index)
            ));
        }

        model.addAttribute("classifierCards", cards);
    }


    /**
     * Страница создания классификатора.
     */
    @GetMapping("/create")
    public String createClassifierPage(Model model) {
        if (!model.containsAttribute("classifierForm")) {
            model.addAttribute(
                    "classifierForm",
                    new ClassifierForm()
            );
        }

        fillCommonModel(model, null);

        return "technology/classifier/create";
    }

    /**
     * Сохранение нового классификатора.
     */
    @PostMapping("/create")
    public String createClassifier(
            @Valid
            @ModelAttribute("classifierForm")
            ClassifierForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        validateConditionalFields(form, errors);

        if (!errors.hasErrors()) {
            try {
                classifierService.create(form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException |
                    IllegalStateException exception
            ) {
                errors.reject(
                        "classifier.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillCommonModel(model, null);

            return "technology/classifier/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Классификатор успешно добавлен"
        );

        return "redirect:/technology/classifier";
    }

    /**
     * Страница выбора классификатора для изменения.
     */
    @GetMapping("/update")
    public String updateClassifierSelectionPage(Model model) {
        if (!model.containsAttribute("classifierForm")) {
            model.addAttribute(
                    "classifierForm",
                    new ClassifierForm()
            );
        }

        fillCommonModel(model, null);

        return "technology/classifier/update";
    }

    /**
     * Страница изменения выбранного классификатора.
     */
    @GetMapping("/update/{id}")
    public String updateClassifierPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "classifierForm",
                    classifierService.getForm(id)
            );

            model.addAttribute(
                    "selectedClassifierId",
                    id
            );

            fillCommonModel(model, id);

            return "technology/classifier/update";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier/update";
        }
    }

    /**
     * Сохранение изменений классификатора.
     */
    @PostMapping("/update/{id}")
    public String updateClassifier(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("classifierForm")
            ClassifierForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        form.setId(id);

        /*
         * Идентификатор нужен для повторного отображения формы,
         * если сервер вернул ошибку валидации.
         */
        model.addAttribute(
                "selectedClassifierId",
                id
        );

        validateConditionalFields(form, errors);

        if (!errors.hasErrors()) {
            try {
                classifierService.update(id, form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException |
                    IllegalStateException exception
            ) {
                errors.reject(
                        "classifier.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillCommonModel(model, id);

            return "technology/classifier/update";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Классификатор успешно изменён"
        );

        return "redirect:/technology/classifier";
    }

    /**
     * Страница выбора классификатора для удаления.
     */
    @GetMapping("/delete")
    public String deleteClassifierSelectionPage(Model model) {
        fillCommonModel(model, null);

        return "technology/classifier/delete";
    }

    /**
     * Страница подтверждения удаления выбранного классификатора.
     */
    @GetMapping("/delete/{id}")
    public String deleteClassifierPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "classifierForm",
                    classifierService.getForm(id)
            );

            model.addAttribute(
                    "selectedClassifierId",
                    id
            );

            fillCommonModel(model, id);

            return "technology/classifier/delete";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier/delete";
        }
    }

    /**
     * Удаление классификатора.
     */
    @PostMapping("/delete/{id}")
    public String deleteClassifier(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            classifierService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Классификатор успешно удалён"
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

        return "redirect:/technology/classifier";
    }

    /**
     * Дополнительная проверка полей,
     * зависящих от выбранного типа изделия.
     */
    private void validateConditionalFields(
            ClassifierForm form,
            BindingResult errors
    ) {
        ProductType productType = form.getProductType();

        if (productType == null) {
            errors.rejectValue(
                    "productType",
                    "classifier.productType.required",
                    "Выберите тип изделия"
            );

            return;
        }

        if (productType == ProductType.SENSOR) {
            if (form.getSensorCatalogId() == null) {
                errors.rejectValue(
                        "sensorCatalogId",
                        "classifier.sensor.required",
                        "Выберите датчик из каталога"
                );
            }

            return;
        }

        if (form.getName() == null || form.getName().isBlank()) {
            errors.rejectValue(
                    "name",
                    "classifier.name.required",
                    "Укажите наименование классификатора"
            );
        }
    }

    /**
     * Добавляет в модель общие данные,
     * используемые страницами классификатора.
     */
    private void fillCommonModel(
            Model model,
            Long excludedClassifierId
    ) {
        model.addAttribute(
                "activePage",
                "classifier"
        );

        model.addAttribute(
                "inclusionModes",
                InclusionMode.values()
        );

        model.addAttribute(
                "productTypes",
                ProductType.values()
        );

        model.addAttribute(
                "measurementUnits",
                MeasurementUnit.values()
        );

        model.addAttribute(
                "sensorCatalogOptions",
                classifierService.findSensors()
        );

        model.addAttribute(
                "classifiers",
                classifierService.findAll()
        );

        model.addAttribute(
                "inclusionOptions",
                classifierService.inclusionOptions(
                        excludedClassifierId
                )
        );

        model.addAttribute(
                "deviceOptions",
                classifierService.deviceOptions(
                        excludedClassifierId
                )
        );
    }
}
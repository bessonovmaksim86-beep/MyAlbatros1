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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.ClassifierForm;
import ru.company.production.entity.InclusionMode;
import ru.company.production.entity.MeasurementUnit;
import ru.company.production.entity.ProductType;
import ru.company.production.service.ProductClassifierService;

@Controller
@RequestMapping("/technology/classifier")
@RequiredArgsConstructor
public class TechnologyClassifierController {

    private final ProductClassifierService classifierService;

    /**
     * Главная страница классификатора.
     * Показывает список существующих классификаторов.
     */
    @GetMapping
    public String classifierPage(Model model) {
        fillCommonModel(model, null);

        return "technology/classifier";
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
     * Страница изменения классификатора.
     */
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
         * Нужно для повторного отображения формы,
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
     * Дополнительная проверка полей, зависящих от типа изделия.
     */
    private void validateConditionalFields(
            ClassifierForm form,
            BindingResult errors
    ) {
        if (form.getProductType() == null) {
            errors.rejectValue(
                    "productType",
                    "classifier.productType.required",
                    "Выберите тип изделия"
            );

            return;
        }

        if (form.getProductType() == ProductType.SENSOR) {
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
     * Общие данные для страниц создания и изменения.
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
    }
}
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
import ru.company.production.entity.MeasurementUnit;
import ru.company.production.entity.ProductType;
import ru.company.production.service.ProductClassifierService;

@Controller
@RequestMapping("/technology/classifier")
@RequiredArgsConstructor
public class TechnologyClassifierController {

    private final ProductClassifierService classifierService;

    @ModelAttribute("classifierForm")
    public ClassifierForm classifierForm() {
        return new ClassifierForm();
    }

    /**
     * Страница со списком классификаторов
     * и формой добавления.
     */
    @GetMapping
    public String classifierPage(Model model) {
        fillModel(model, null, false);
        return "technology/classifier";
    }

    /**
     * Добавление классификатора.
     */
    @PostMapping
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
            fillModel(model, null, false);
            return "technology/classifier";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Классификатор успешно добавлен"
        );

        return "redirect:/technology/classifier";
    }

    /**
     * Открытие формы изменения классификатора.
     */
    @GetMapping("/{id}/edit")
    public String editClassifier(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "classifierForm",
                    classifierService.getForm(id)
            );

            fillModel(model, id, true);

            return "technology/classifier";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier";
        }
    }

    /**
     * Сохранение изменений классификатора.
     */
    @PostMapping("/{id}")
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
            fillModel(model, id, true);
            return "technology/classifier";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Классификатор успешно изменён"
        );

        return "redirect:/technology/classifier";
    }

    private void validateConditionalFields(
            ClassifierForm form,
            BindingResult errors
    ) {
        if (form.getProductType() == null) {
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

    private void fillModel(
            Model model,
            Long editedClassifierId,
            boolean editMode
    ) {
        model.addAttribute("activePage", "classifier");
        model.addAttribute("editMode", editMode);

        model.addAttribute(
                "productTypes",
                ProductType.values()
        );

        model.addAttribute(
                "measurementUnits",
                MeasurementUnit.values()
        );

        model.addAttribute(
                "sensorCatalog",
                classifierService.findSensors()
        );

        model.addAttribute(
                "classifiers",
                classifierService.findAll()
        );

        model.addAttribute(
                "inclusionOptions",
                classifierService.inclusionOptions(
                        editedClassifierId
                )
        );
    }
}
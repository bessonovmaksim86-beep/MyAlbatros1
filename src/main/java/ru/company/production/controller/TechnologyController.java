package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.OperationForm;
import ru.company.production.entity.OperationType;
import ru.company.production.repository.DepartmentRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.WorkshopRepository;
import ru.company.production.service.OperationService;

@Controller
@RequestMapping("/technology")
@RequiredArgsConstructor
public class TechnologyController {

    private final OperationService operationService;
    private final OperationRepository operationRepository;
    private final WorkshopRepository workshopRepository;
    private final DepartmentRepository departmentRepository;

    @ModelAttribute("operationForm")
    public OperationForm operationForm() {
        return new OperationForm();
    }

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/technology/general";
    }

    @GetMapping("/general")
    public String general(Model model) {
        model.addAttribute("activePage", "general");
        return "technology/general";
    }

    @GetMapping("/techprocess")
    public String techprocess(Model model) {
        model.addAttribute("activePage", "techprocess");
        return "technology/techprocess";
    }

    @GetMapping("/operation")
    public String operation(Model model) {
        fillOperations(model);
        model.addAttribute("activePage", "operation");
        return "technology/operation";
    }

    @GetMapping("/operations")
    public String oldOperations() {
        return "redirect:/technology/operation";
    }

    @PostMapping("/operation")
    public String addOperation(
            @Valid
            @ModelAttribute("operationForm")
            OperationForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirect
    ) {
        if (!errors.hasErrors()) {
            try {
                operationService.create(form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                errors.reject(
                        "operation.error",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillOperations(model);
            model.addAttribute("activePage", "operation");
            return "technology/operation";
        }

        redirect.addFlashAttribute(
                "successMessage",
                "Операция успешно добавлена"
        );

        return "redirect:/technology/operation";
    }

    private void fillOperations(Model model) {
        model.addAttribute(
                "operations",
                operationRepository.findAllByOrderByNameAsc()
        );

        model.addAttribute(
                "operationCount",
                operationRepository.count()
        );

        model.addAttribute(
                "operationTypes",
                OperationType.values()
        );

        model.addAttribute(
                "workshops",
                workshopRepository
                        .findAllByActiveTrueOrderByNameAsc()
        );

        model.addAttribute(
                "departments",
                departmentRepository
                        .findByActiveTrueOrderByNameAsc()
        );
    }
}
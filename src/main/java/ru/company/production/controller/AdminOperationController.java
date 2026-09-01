package ru.company.production.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.CreateOperationForm;
import ru.company.production.service.AdminOperationService;

@Controller
@RequestMapping("/admin/operations")
@RequiredArgsConstructor
public class AdminOperationController {

    private final AdminOperationService operationService;

    @GetMapping
    public String page(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateOperationForm());
        }

        model.addAttribute("operations", operationService.findAll());
        return "admin/operations";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") CreateOperationForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (!bindingResult.hasErrors()) {
            try {
                operationService.create(form);
            } catch (IllegalArgumentException exception) {
                bindingResult.reject("create", exception.getMessage());
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute(
                    "operations",
                    operationService.findAll()
            );
            return "admin/operations";
        }

        redirectAttributes.addFlashAttribute(
                "success",
                "Операция успешно добавлена"
        );

        return "redirect:/admin/operations";
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            operationService.delete(id);
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Операция удалена"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/operations";
    }
}
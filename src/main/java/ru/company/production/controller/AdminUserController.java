package ru.company.production.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.CreateUserForm;
import ru.company.production.entity.Role;
import ru.company.production.service.AdminUserService;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService userService;

    @GetMapping
    public String page(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateUserForm());
        }

        fillModel(model);
        return "admin/users";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") CreateUserForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (!bindingResult.hasErrors()) {
            try {
                userService.create(form);
            } catch (IllegalArgumentException exception) {
                bindingResult.reject("create", exception.getMessage());
            }
        }

        if (bindingResult.hasErrors()) {
            fillModel(model);
            return "admin/users";
        }

        redirectAttributes.addFlashAttribute(
                "success",
                "Пользователь успешно добавлен"
        );

        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        try {
            userService.delete(id, authentication.getName());
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Пользователь удалён"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/users";
    }

    private void fillModel(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", Role.values());
    }
}
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
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.entity.UserType;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.service.AdminUserService;
import ru.company.production.service.PasswordGenerator;

import java.util.Map;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService userService;
    private final PasswordGenerator passwordGenerator;

    private final OrganizationUnitRepository unitRepository;
    private final ProductionServiceRepository serviceRepository;

    @GetMapping
    public String page(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute(
                    "form",
                    new CreateUserForm()
            );
        }

        fillModel(model);

        return "admin/users";
    }

    @GetMapping("/suggest-login")
    @ResponseBody
    public Map<String, String> suggestLogin(
            @RequestParam(defaultValue = "") String fullName
    ) {
        return Map.of(
                "login",
                userService.suggestLogin(fullName)
        );
    }

    @GetMapping("/generate-password")
    @ResponseBody
    public Map<String, String> generatePassword() {
        return Map.of(
                "password",
                passwordGenerator.generate()
        );
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") CreateUserForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                AppUser created = userService.create(form);

                redirectAttributes.addFlashAttribute(
                        "success",
                        "Пользователь создан. Логин: "
                                + created.getUsername()
                );

                return "redirect:/admin/users";

            } catch (IllegalArgumentException exception) {
                bindingResult.reject(
                        "create",
                        exception.getMessage()
                );
            }
        }

        fillModel(model);

        return "admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            userService.delete(
                    id,
                    authentication.getName()
            );

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
        model.addAttribute(
                "users",
                userService.findAll()
        );

        model.addAttribute(
                "roles",
                Role.values()
        );

        model.addAttribute(
                "userTypes",
                UserType.values()
        );

        model.addAttribute(
                "services",
                serviceRepository
                        .findAllByActiveTrueOrderByCodeAsc()
        );

        model.addAttribute(
                "organizationUnits",
                unitRepository
                        .findAllByActiveTrueOrderByNameAsc()
        );
    }
}
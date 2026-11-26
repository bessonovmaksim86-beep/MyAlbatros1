package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.StaffMemberView;
import ru.company.production.dto.WorkWithoutPlanForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.WorkWithoutPlan;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopProductivityService;
import ru.company.production.service.WorkshopStaffService;
import ru.company.production.service.WorkWithoutPlanService;

import java.time.LocalDate;
import java.util.List;

/**
 * Работа без плана: ввод и реестр.
 *
 * Здесь руководитель цеха вручную вносит выработку за операции,
 * которых не было в производственном плане. Записи используются
 * только в статистике производительности и не влияют ни на один
 * другой раздел системы.
 */
@Controller
@RequestMapping("/workshop/without-plan")
@RequiredArgsConstructor
public class WorkWithoutPlanController {

    private final WorkWithoutPlanService service;
    private final WorkshopProductivityService productivityService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    @GetMapping({"", "/"})
    public String index(
            WorkWithoutPlanForm form,
            Authentication authentication,
            Model model
    ) {
        AppUser supervisor = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (supervisor == null) {
            return "redirect:/login";
        }

        List<Long> unitIds = staffService.unitScope(supervisor);
        List<StaffMemberView> employees = staffService.findStaff(unitIds);

        model.addAttribute("employees", employees);
        model.addAttribute("form", form);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("activePage", "withoutPlan");

        return "workshop/without-plan/index";
    }

    @PostMapping
    public String save(
            WorkWithoutPlanForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser supervisor = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (supervisor == null) {
            return "redirect:/login";
        }

        try {
            service.save(form, supervisor);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Выработка записана"
            );
        } catch (
                IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/workshop/without-plan";
    }

    @PostMapping("/delete")
    public String delete(
            @RequestParam("id") Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            service.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Запись удалена"
            );
        } catch (Exception exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/workshop/without-plan";
    }
}

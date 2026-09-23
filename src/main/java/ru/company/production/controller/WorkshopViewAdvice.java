package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopStaffService;

/**
 * Данные текущего руководителя цеха для всех страниц модуля:
 * верхняя панель, боковое меню и граница цеха (workshopUnitId).
 */
@ControllerAdvice(
        assignableTypes = {
                WorkshopStaffController.class,
                WorkshopCalendarController.class
        }
)
@RequiredArgsConstructor
public class WorkshopViewAdvice {

    private final UserRepository userRepository;
    private final WorkshopStaffService staffService;

    @ModelAttribute
    public void addWorkshopContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        /*
         * Подразделение и служба читаются у руководителя сразу после
         * запроса: open-in-view отключён, и без EntityGraph обращение к
         * lazy-связи у detached-объекта заканчивалось бы
         * LazyInitializationException на каждой странице модуля.
         */
        AppUser supervisor = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (supervisor == null) {
            return;
        }

        model.addAttribute("currentUser", supervisor);

        model.addAttribute(
                "userDisplayName",
                WorkshopStaffService.displayName(supervisor)
        );

        String specialty = supervisor.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );

        Long unitId = staffService.workshopUnitId(supervisor);

        model.addAttribute("workshopUnitId", unitId);
        model.addAttribute(
                "workshopName",
                staffService.workshopName(supervisor)
        );
        model.addAttribute(
                "staffCount",
                staffService.findEmployees(unitId).size()
        );
    }
}

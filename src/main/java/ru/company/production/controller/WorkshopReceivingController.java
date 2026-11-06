package ru.company.production.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.UnitHandover;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.UnitHandoverService;
import ru.company.production.service.WorkshopStaffService;

/**
 * Приёмка позиций со склада: цех подтверждает, что увезённое у него.
 *
 * Склад вносит ячейку в заказ и снимает её с полки не сразу, а после подписи получателя —
 * до неё номер висит здесь. Пока подтверждения нет, кладовщик видит позицию в реестре с
 * плашкой «Передана, ждёт подтверждения» и не может выдать её вторично.
 *
 * Проводка идёт redirect'ом: повторный F5 не должен оформить вторую приёмку того же номера
 * и вторую запись в журнале движения.
 *
 * Подразделение берётся из учётной записи, а не из формы: список и так собирается по нему,
 * но ссылка приёма одна на все цеха, и без проверки в сервисе мастер закрыл бы чужую приёмку.
 */
@Controller
@RequestMapping("/workshop/receiving")
@RequiredArgsConstructor
public class WorkshopReceivingController {

    private final UnitHandoverService handoverService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    /** Очередь ожидания: позиции, переданные подразделению и не подтверждённые им. */
    @GetMapping({"", "/"})
    public String list(Authentication authentication, Model model) {

        AppUser supervisor = currentUser(authentication);
        Long unitId = supervisor == null ? null : staffService.workshopUnitId(supervisor);

        List<UnitHandover> pending = handoverService.pendingForUnit(unitId);

        model.addAttribute("handovers", pending);
        model.addAttribute("pendingCount", pending.size());

        return "workshop/receiving";
    }

    /** Подтвердить получение позиции. */
    @PostMapping("/accept")
    public String accept(@RequestParam Long id,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {

        String error = handoverService.accept(id, unitIdOf(authentication),
                currentUser(authentication));

        if (error == null) {
            redirectAttributes.addFlashAttribute("successMessage",
                    "Позиция принята: со склада она снята");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", error);
        }

        return "redirect:/workshop/receiving";
    }

    /** Вернуть позицию складу: она снова ляжет на полку в доступный остаток. */
    @PostMapping("/reject")
    public String reject(@RequestParam Long id,
                         @RequestParam(required = false) String reason,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {

        String error = handoverService.reject(id, unitIdOf(authentication), reason,
                currentUser(authentication));

        if (error == null) {
            redirectAttributes.addFlashAttribute("successMessage",
                    "Позиция возвращена складу");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", error);
        }

        return "redirect:/workshop/receiving";
    }

    private Long unitIdOf(Authentication authentication) {
        AppUser supervisor = currentUser(authentication);
        return supervisor == null ? null : staffService.workshopUnitId(supervisor);
    }

    /**
     * Учётная запись с подразделениями: open-in-view выключен, поэтому lazy-связь
     * подразделения читается только этим запросом.
     */
    private AppUser currentUser(Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);
    }
}

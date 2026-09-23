package ru.company.production.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.service.AdminImpersonationService;

/**
 * Вход администратора в интерфейс другой роли и возврат в свой.
 *
 * Прямой переход находится под {@code /admin/**} и потому доступен только
 * администратору. Возврат вынесен в {@code /switch/exit}: в режиме подмены у
 * администратора прав на {@code /admin/**} уже нет, и ссылка на возврат
 * перестала бы работать именно там, где она нужнее всего.
 */
@Controller
@RequiredArgsConstructor
public class AdminSwitchController {

    private final AdminImpersonationService impersonationService;

    @PostMapping("/admin/switch/{userId}")
    public String enter(
            @PathVariable Long userId,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        String targetUrl = impersonationService.start(
                userId,
                request,
                response
        );

        if (targetUrl == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Не удалось войти в интерфейс выбранной роли: "
                            + "учётная запись не найдена или недоступна"
            );

            return "redirect:/admin/roles";
        }

        return "redirect:" + targetUrl;
    }

    @PostMapping("/switch/exit")
    public String exit(
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        if (!impersonationService.exit(request, response)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Режим входа в другую роль не активен"
            );
        }

        return "redirect:/admin/general";
    }
}

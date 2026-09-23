package ru.company.production.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    /*
     * Вход в систему — только форма аутентификации. Старая «главная»
     * вела на статическую страницу-заглушку, которой в шаблонах нет;
     * теперь корень отдаёт рабочий стол, куда success-handler всё равно
     * приводит по роли.
     */
    @GetMapping("/")
    public String index() {
        return "redirect:/home";
    }

    /*
     * Адрес /admin принадлежит AdminModuleController: он переадресует на
     * сводку. Дублирующий метод здесь ломал запуск приложения
     * неоднозначным маппингом.
     */
    @GetMapping("/login")
    public String login(HttpServletRequest request) {
        Object csrfAttribute =
                request.getAttribute(CsrfToken.class.getName());

        if (csrfAttribute instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }

        return "login";
    }
}

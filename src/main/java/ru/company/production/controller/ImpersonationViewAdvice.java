package ru.company.production.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.dto.ImpersonationInfo;
import ru.company.production.service.AdminImpersonationService;

/**
 * Режим «вход в другую роль» для всех страниц производственной системы.
 *
 * Данные читаются из сессии, а не из базы: плашка обязана отрисовываться и в
 * диспетчерском отделе, и в цехе, где лишнего запроса к учётным записям быть
 * не должно. Отдельный совет нужен ещё и потому, что у каждого модуля свой
 * {@code @ControllerAdvice}, и дублировать в них признак подмены означало бы
 * забыть четвёртый модуль при его появлении.
 */
@ControllerAdvice
public class ImpersonationViewAdvice {

    @ModelAttribute
    public void addImpersonation(
            HttpServletRequest request,
            Model model
    ) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return;
        }

        if (session.getAttribute(
                AdminImpersonationService.SESSION_INFO)
                instanceof ImpersonationInfo info) {

            model.addAttribute("impersonation", info);
        }
    }
}

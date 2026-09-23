package ru.company.production.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ModuleRoutes;

import java.io.IOException;

/**
 * Направление пользователя в его модуль сразу после входа.
 *
 * Маршрут берётся из {@link ModuleRoutes} — того же справочника, по
 * которому администратор входит в чужой интерфейс. Раньше здесь
 * повторялся перечень адресов и дополнительно сверялось название
 * подразделения: «производственный отдел», «отдел качества» и «склад»
 * вели на страницы, которых в системе нет, а роль диспетчера попадала на
 * рабочий стол вместо диспетчерского отдела.
 */
@Component
@RequiredArgsConstructor
public class DepartmentAuthenticationSuccessHandler
        implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {

        /*
         * Подразделение нужно для границ модулей, а open-in-view отключён:
         * связи загружаются тем же запросом, что и учётная запись.
         */
        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Вторично вызванный пользователь не найден"
                        )
                );

        String targetUrl = determineTargetUrl(user);

        response.sendRedirect(
                request.getContextPath() + targetUrl
        );
    }

    private String determineTargetUrl(AppUser user) {
        if (user.getRole() == Role.ADMIN) {
            return "/admin";
        }

        return ModuleRoutes.destinationFor(user.getRole()).url();
    }
}
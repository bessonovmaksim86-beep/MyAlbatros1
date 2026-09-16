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

import java.io.IOException;
import java.util.Locale;

import static ru.company.production.entity.Role.SDP_SERVICE_HEAD_DISPATCHER;
import static ru.company.production.entity.Role.SDP_SERVICE_HEAD_TECHNOLOGY;

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

        AppUser user = userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Авторизованный пользователь не найден"
                        )
                );

        String targetUrl = determineTargetUrl(user);
        System.out.println(
                "LOGIN SUCCESS: username=" + authentication.getName()
                        + ", role=" + user.getRole()
                        + ", targetUrl=" + targetUrl
        );
        response.sendRedirect(
                request.getContextPath() + targetUrl
        );
    }

    private String determineTargetUrl(AppUser user) {

        if (user.getRole() == Role.ADMIN) {
            return "/admin";
        }
        if (user.getRole() == SDP_SERVICE_HEAD_TECHNOLOGY) {
            return "/technology";
        }
        if (user.getRole() == SDP_SERVICE_HEAD_DISPATCHER) {
            return "/dispatcher";
        }
        if (user.getOrganizationUnit() == null) {
            return "/";
        }

        /*
         * Если OrganizationUnit является сущностью,
         * используйте её код или название.
         *
         * Например:
         * user.getOrganizationUnit().getCode()
         */
        String unit = user.getOrganizationUnit()
                .getName()
                .trim()
                .toLowerCase(Locale.ROOT);

        return switch (unit) {
            case "технологический отдел",
                 "отдел технологов",
                 "технологи" -> "/technology";

            case "производственный отдел",
                 "производство" -> "/production";

            case "отдел качества",
                 "качество" -> "/quality";

            case "склад",
                 "складской отдел" -> "/warehouse";

            default -> "/";
        };

    }
}
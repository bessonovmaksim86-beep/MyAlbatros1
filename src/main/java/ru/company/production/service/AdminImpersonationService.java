package ru.company.production.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import ru.company.production.dto.ImpersonationInfo;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;

import java.util.List;

/**
 * Вход администратора в интерфейс другой роли.
 *
 * Администратор базы не привязан к службе и подразделению, поэтому сам по себе
 * он не может открыть ни диспетчерский отдел, ни цех: модули определяют свои
 * границы по учётной записи (см. {@code WorkshopStaffService}). Решение —
 * временно подменить аутентификацию на реальную учётную запись выбранной роли:
 * проверка прав и границы подразделений продолжают работать как обычно, а
 * администратор видит ровно то, что видит сотрудник.
 *
 * Подмена живёт в сессии, поэтому возврат выполняется явным запросом
 * {@code /switch/exit} и не зависит от кнопок «назад» в браузере.
 */
@Service
@RequiredArgsConstructor
public class AdminImpersonationService {

    /** Атрибут сессии с логином администратора, из которого выполнены в чужую роль. */
    public static final String SESSION_ADMIN = "impersonationAdmin";

    /** Атрибут сессии для верхней плашки «вы в интерфейсе другой роли». */
    public static final String SESSION_INFO = "impersonation";

    private final UserRepository userRepository;

    /**
     * Тот же состав репозитория, что использует Spring Security по умолчанию:
     * без записи в сессию подмена доживёт только до конца текущего запроса.
     */
    private final SecurityContextRepository securityContextRepository =
            new DelegatingSecurityContextRepository(
                    new RequestAttributeSecurityContextRepository(),
                    new HttpSessionSecurityContextRepository()
            );

    /**
     * Подпускает администратора к интерфейсу выбранной учётной записи.
     *
     * @return адрес модуля, куда отправляем администратора, либо {@code null},
     *         если подмена невозможна (запрос не от администратора, учётная
     *         запись не найдена или это ещё один администратор).
     */
    public String start(
            Long userId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AppUser admin = currentAdmin();

        if (admin == null || userId == null) {
            return null;
        }

        AppUser target = userRepository.findById(userId)
                .filter(user -> user.isActive() && user.getRole() != Role.ADMIN)
                .orElse(null);

        if (target == null) {
            return null;
        }

        HttpSession session = request.getSession(true);

        /*
         * Исходный администратор запоминается один раз: повторный переход уже
         * из «чужого» интерфейса не должен затирать путь обратно.
         */
        session.setAttribute(SESSION_ADMIN, admin.getUsername());
        session.setAttribute(
                SESSION_INFO,
                new ImpersonationInfo(
                        admin.getUsername(),
                        target.getRole().getDisplayName(),
                        displayName(target)
                )
        );

        authenticate(target, request, response);

        return ModuleRoutes.destinationFor(target.getRole()).url();
    }

    /**
     * Возвращает администратора в собственный интерфейс.
     *
     * @return {@code true}, если в сессии был режим подмены и он снят.
     */
    public boolean exit(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return false;
        }

        Object adminUsername = session.getAttribute(SESSION_ADMIN);

        session.removeAttribute(SESSION_ADMIN);
        session.removeAttribute(SESSION_INFO);

        if (!(adminUsername instanceof String username)) {
            return false;
        }

        userRepository.findByUsernameIgnoreCase(username)
                .ifPresent(admin -> authenticate(admin, request, response));

        return true;
    }

    /**
     * Учётная запись, от имени которой сейчас выполняется запрос.
     */
    public AppUser currentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getName() == null) {
            return null;
        }

        return userRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElse(null);
    }

    /**
     * Действующие учётные записи, под которые разрешён вход, в порядке ролей:
     * администратор исключён — входить под свою же роль не имеет смысла.
     */
    public List<AppUser> findSwitchableUsers() {
        return userRepository
                .findAllByOrderByFullNameAscUsernameAsc()
                .stream()
                .filter(AppUser::isActive)
                .filter(user -> user.getRole() != Role.ADMIN)
                .toList();
    }

    public String displayName(AppUser user) {
        String fullName = user.getFullName();

        return fullName == null || fullName.isBlank()
                ? user.getUsername()
                : fullName.trim();
    }

    private AppUser currentAdmin() {
        AppUser user = currentUser();

        return user != null && user.getRole() == Role.ADMIN && user.isActive()
                ? user
                : null;
    }

    /**
     * Собирает аутентификацию так же, как {@code SecurityConfig#userDetailsService}:
     * та же структура principal и то же право {@code ROLE_<роль>}, чтобы
     * {@code sec:authorize} и {@code hasRole} в модулях работали без исключений.
     */
    private void authenticate(
            AppUser user,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                User.builder()
                        .username(user.getUsername())
                        /*
                         * Хеш не нужен: пароль уже проверен администратором при
                         * входе. Значение намеренно в виде метки, а не BCrypt, —
                         * токен нельзя использовать для повторной аутентификации.
                         */
                        .password("{impersonated}")
                        .roles(user.getRole().name())
                        .disabled(!user.isActive())
                        .build(),
                null,
                List.of(new SimpleGrantedAuthority(
                        "ROLE_" + user.getRole().name()
                ))
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        securityContextRepository.saveContext(context, request, response);
    }
}

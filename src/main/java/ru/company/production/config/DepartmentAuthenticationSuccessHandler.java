package ru.company.production.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ModuleRoutes;
import ru.company.production.service.ScheduleRescheduleService;
import ru.company.production.service.WorkshopStaffService;

import java.io.IOException;
import java.util.List;

/**
 * Направление пользователя в его модуль сразу после входа.
 *
 * Маршрут берётся из {@link ModuleRoutes} — того же справочника, по
 * которому администратор входит в чужой интерфейс. Раньше здесь
 * повторялся перечень адресов и дополнительно сверялось название
 * подразделения: «производственный отдел», «отдел качества» и «склад»
 * вели на страницы, которых в системе нет, а роль диспетчера попадала на
 * рабочий стол вместо диспетчерского отдела.
 *
 * Кроме того, при входе проверяется, не накопились ли непроведённые
 * операции с прошедших дат: если пользователь не закрывал приложение
 * на выходные, строки графика с прошедшей planned_date выпадут из
 * любого интервала экрана распределения. Их сдвигают на ближайший
 * свободный день каждого исполнителя, а прежнюю дату сохраняют для
 * расчёта просрочки.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DepartmentAuthenticationSuccessHandler
        implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final ScheduleRescheduleService rescheduleService;
    private final WorkshopStaffService staffService;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {

        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Вторично вызванный пользователь не найден"
                        )
                );

        /*
         * Перенос просроченных работ выполняется в границах
         * подразделения войдящего руководителя — тем же
         * {@link WorkshopStaffService#unitScope}, что и экран
         * распределения: перенос чужих работ оставил бы мастера без
         * ответа, куда делись его строки плана. Работник без
         * подразделения (администратор, диспетчер) ничего не переносит.
         *
         * Ошибка переноса не имеет права отменить вход: пользователь
         * остался бы на странице логина без объяснения, а просрочку
         * видно и без переноса — она просто не сдвинулась.
         */
        try {
            List<Long> scope = staffService.unitScope(user);

            if (!scope.isEmpty()) {
                rescheduleService.rescheduleOverdueRows(scope);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Перенос непроведённых работ при входе «{}» не выполнен: {}",
                    authentication.getName(),
                    exception.getMessage()
            );
        }

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
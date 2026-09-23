package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.company.production.dto.RoleAccessView;
import ru.company.production.dto.RoleMenuEntry;
import ru.company.production.dto.RoleMenuSection;
import ru.company.production.dto.SwitchUserView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.Role;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сводка администратора базы и список ролей для входа в чужой интерфейс.
 *
 * Модули системы (диспетчерский отдел, технологическое бюро, цех) считают
 * показатели сами; администратору нужны сквозные цифры по всей базе, поэтому
 * они собраны здесь в один запрос на раздел, а не в счётчики по шаблону.
 */
@Service
@RequiredArgsConstructor
public class AdminConsoleService {

    private final UserRepository userRepository;
    private final OperationRepository operationRepository;
    private final ProductClassifierRepository classifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final WorkPlaceRepository workPlaceRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final WorkshopStaffBindingRepository staffBindingRepository;
    private final CustomerService customerService;
    private final OrderPriorityService orderPriorityService;
    private final AdminImpersonationService impersonationService;

    /**
     * Роли в боковом меню администратора, сгруппированные по модулям.
     *
     * Порядок тот же, что у справочника {@link Role}, но группы «со своим
     * интерфейсом» идут первыми, а общий рабочий стол — в конце: администратор
     * почти всегда проверяет конкретный отдел, а не роль без модуля. Внутри
     * роли вход выполняется под первую действующую учётную запись — границы
     * цеха или отдела модуль берёт из сотрудника, а не из роли.
     */
    public List<RoleMenuSection> roleMenuSections() {
        Map<Role, List<SwitchUserView>> usersByRole = usersByRole();

        Map<String, List<RoleMenuEntry>> grouped = new LinkedHashMap<>();
        List<RoleMenuEntry> workplace = new ArrayList<>();

        for (Role role : Role.values()) {
            if (role == Role.ADMIN) {
                continue;
            }

            RoleMenuEntry entry = toMenuEntry(
                    role,
                    usersByRole.getOrDefault(role, List.of())
            );

            if (ModuleRoutes.hasOwnModule(role)) {
                grouped.computeIfAbsent(
                        ModuleRoutes.destinationFor(role).name(),
                        key -> new ArrayList<>()
                ).add(entry);
            } else {
                workplace.add(entry);
            }
        }

        List<RoleMenuSection> sections = new ArrayList<>();

        grouped.forEach(
                (title, entries) -> sections.add(
                        new RoleMenuSection(title, entries)
                )
        );

        if (!workplace.isEmpty()) {
            sections.add(
                    new RoleMenuSection(ModuleRoutes.WORKPLACE.name(), workplace)
            );
        }

        return sections;
    }

    /**
     * Роли в порядке справочника вместе со всеми действующими учётными
     * записями и модулем, который роль открывает. Внутри роли учётные записи
     * идут по ФИО — именно под выбранную выполняется вход.
     */
    public List<RoleAccessView> roleAccesses() {
        Map<Role, List<SwitchUserView>> usersByRole = usersByRole();

        List<RoleAccessView> accesses = new ArrayList<>();

        for (Role role : Role.values()) {
            if (role == Role.ADMIN) {
                continue;
            }

            List<SwitchUserView> users =
                    usersByRole.getOrDefault(role, List.of());

            accesses.add(new RoleAccessView(
                    role.name(),
                    role.getDisplayName(),
                    users.size(),
                    ModuleRoutes.destinationFor(role).name(),
                    users
            ));
        }

        return accesses;
    }

    /**
     * Действующие учётные записи, сгруппированные по роли. Используется и
     * страницей «Роли и вход», и меню: список один и тот же, различается
     * только глубина детализации.
     */
    private Map<Role, List<SwitchUserView>> usersByRole() {
        Map<Role, List<SwitchUserView>> usersByRole = new LinkedHashMap<>();

        for (AppUser user : impersonationService.findSwitchableUsers()) {
            usersByRole
                    .computeIfAbsent(user.getRole(), key -> new ArrayList<>())
                    .add(toSwitchUser(user));
        }

        return usersByRole;
    }

    private RoleMenuEntry toMenuEntry(
            Role role,
            List<SwitchUserView> users
    ) {
        SwitchUserView representative = users.isEmpty() ? null : users.get(0);

        return new RoleMenuEntry(
                role.name(),
                role.getDisplayName(),
                representative == null ? null : representative.id(),
                representative == null ? null : representative.displayName(),
                users.size()
        );
    }

    /**
     * Подпись учётной записи для кнопки входа: ФИО, логин, должность и
     * подразделение — администратор должен понимать, под кого именно входит.
     */
    private SwitchUserView toSwitchUser(AppUser user) {
        String specialty = user.getSpecialty();
        OrganizationUnit unit = user.getOrganizationUnit();

        return new SwitchUserView(
                user.getId(),
                impersonationService.displayName(user),
                user.getUsername(),
                specialty == null || specialty.isBlank()
                        ? null
                        : specialty,
                unit == null
                        ? null
                        : unit.getService().getCode() + ": " + unit.getName()
        );
    }

    public long userCount() {
        return userRepository.count();
    }

    public long activeUserCount() {
        return userRepository.findAllByOrderByFullNameAscUsernameAsc()
                .stream()
                .filter(AppUser::isActive)
                .count();
    }

    public long administratorCount() {
        return userRepository.countByRole(Role.ADMIN);
    }

    public long roleCount() {
        return Role.values().length;
    }

    public long operationCount() {
        return operationRepository.count();
    }

    public long classifierCount() {
        return classifierRepository.count();
    }

    public long techProcessCount() {
        return techProcessRepository.countByActiveTrue();
    }

    public long workPlaceCount() {
        return workPlaceRepository.countByActiveTrue();
    }

    public long customerOrderCount() {
        return customerOrderRepository.countByActiveTrue();
    }

    public long productionOrderCount() {
        return productionOrderRepository.countByActiveTrue();
    }

    public long customerCount() {
        return customerService.countActive();
    }

    public long priorityCount() {
        return orderPriorityService.countActive();
    }

    public long staffBindingCount() {
        return staffBindingRepository.count();
    }
}

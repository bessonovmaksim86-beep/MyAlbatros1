package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AdminUserView;
import ru.company.production.dto.CreateUserForm;
import ru.company.production.entity.*;

import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.repository.UserRepository;

import java.util.List;

import static ru.company.production.entity.Role.SDP_SERVICE_HEAD_TECHNOLOGY;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;

    private final OrganizationUnitRepository unitRepository;

    /*
     * Эта зависимость отсутствовала.
     * Из-за этого serviceRepository не находился компилятором.
     */
    private final ProductionServiceRepository serviceRepository;

    private final PasswordEncoder passwordEncoder;
    private final PasswordVaultService passwordVaultService;
    private final LoginGenerator loginGenerator;

    @Transactional(readOnly = true)
    public List<AdminUserView> findAll() {
        return userRepository.findAll()
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public String suggestLogin(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }

        return loginGenerator.generateUnique(fullName.trim());
    }

    @Transactional
    public AppUser create(CreateUserForm form) {
        String fullName = normalizeRequired(
                form.getFullName(),
                "Введите ФИО пользователя"
        );

        String specialty = normalizeRequired(
                form.getSpecialty(),
                "Введите должность пользователя"
        );

        String password = normalizeRequired(
                form.getPassword(),
                "Введите или сгенерируйте пароль"
        );

        if (form.getRole() == null) {
            throw new IllegalArgumentException(
                    "Выберите роль пользователя"
            );
        }

        AppUser user = new AppUser();

        String username = loginGenerator.generateUnique(fullName);

        user.setUsername(username);
        user.setFullName(fullName);
        user.setSpecialty(specialty);
        user.setRole(form.getRole());
        user.setActive(true);

        applyOrganizationData(user, form);

        /*
         * passwordHash используется Spring Security
         * для проверки введённого пароля.
         */
        user.setPasswordHash(
                passwordEncoder.encode(password)
        );

        /*
         * passwordCiphertext используется только в том случае,
         * если администратору требуется показать созданный пароль.
         */
        user.setPasswordCiphertext(
                passwordVaultService.encrypt(password)
        );

        return userRepository.save(user);
    }

    @Transactional
    public void delete(
            Long id,
            String currentUsername
    ) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Пользователь не найден"
                        )
                );

        if (currentUsername != null
                && user.getUsername()
                .equalsIgnoreCase(currentUsername)) {

            throw new IllegalArgumentException(
                    "Нельзя удалить собственную учётную запись"
            );
        }

        if (user.getRole() == Role.ADMIN
                && userRepository.countByRole(Role.ADMIN) <= 1) {

            throw new IllegalArgumentException(
                    "Нельзя удалить последнего администратора"
            );
        }

        userRepository.delete(user);
    }

    private AdminUserView toView(AppUser user) {
        String unitName = buildOrganizationName(user);

        /*
         * Защита для пользователей, которые были добавлены
         * до появления поля passwordCiphertext.
         */
        String visiblePassword = null;

        if (user.getPasswordCiphertext() != null
                && !user.getPasswordCiphertext().isBlank()) {

            try {
                visiblePassword = passwordVaultService.decrypt(
                        user.getPasswordCiphertext()
                );
            } catch (RuntimeException exception) {
                /*
                 * Старое или повреждённое значение не должно
                 * ломать всю страницу администрирования.
                 */
                visiblePassword = null;
            }
        }

        return new AdminUserView(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getSpecialty(),
                unitName,
                user.getRole().getDisplayName(),
                user.isActive(),
                visiblePassword
        );
    }

    private String buildOrganizationName(AppUser user) {
        if (user.getRole() == Role.ADMIN) {
            return "Администратор всей базы";
        }

        ProductionService service = user.getService();
        OrganizationUnit unit = user.getOrganizationUnit();

        if (service == null) {
            return "Не назначен";
        }

        String serviceName =
                service.getCode() + ": " + service.getName();

        /*
         * Например, для СГМ подразделение может отсутствовать.
         */
        if (unit == null) {
            return serviceName;
        }

        return serviceName
                + " / "
                + unit.getCode()
                + ": "
                + unit.getName();
    }

    private void applyOrganizationData(
            AppUser user,
            CreateUserForm form
    ) {
        /*
         * Администратор имеет доступ ко всей базе
         * и не относится к конкретной службе
         * или подразделению.
         */
        if (form.getRole() == Role.ADMIN) {
            user.setService(null);
            user.setOrganizationUnit(null);
            user.setRoleUser(null);
            return;
        }

        if (form.getRoleUser() == null) {
            throw new IllegalArgumentException(
                    "Выберите тип пользователя"
            );
        }

        if (form.getServiceId() == null) {
            throw new IllegalArgumentException(
                    "Выберите службу"
            );
        }

        ProductionService service =
                serviceRepository
                        .findById(form.getServiceId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Выбранная служба не найдена"
                                )
                        );

        if (!service.isActive()) {
            throw new IllegalArgumentException(
                    "Выбранная служба неактивна"
            );
        }

        OrganizationUnit unit = null;

        if (form.getOrganizationUnitId() != null) {
            unit = unitRepository
                    .findById(form.getOrganizationUnitId())
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Выбранное подразделение не найдено"
                            )
                    );

            if (!unit.isActive()) {
                throw new IllegalArgumentException(
                        "Выбранное подразделение неактивно"
                );
            }

            if (unit.getService() == null
                    || !unit.getService()
                    .getId()
                    .equals(service.getId())) {

                throw new IllegalArgumentException(
                        "Подразделение не относится "
                                + "к выбранной службе"
                );
            }
        }

        /*
         * Руководитель службы относится к службе целиком
         * и не должен быть привязан к подразделению.
         */
        if (form.getRoleUser() == RoleUser.SERVICE_HEAD
                && unit != null) {

            throw new IllegalArgumentException(
                    "Руководитель службы не должен "
                            + "иметь подразделение"
            );
        }

        /*
         * Руководитель подразделения обязательно
         * должен иметь выбранное подразделение.
         */
        if (form.getRoleUser()
                == RoleUser.HEAD_OF_WORKSHOP_OR_DEPARTMENT
                && unit == null) {

            throw new IllegalArgumentException(
                    "Для руководителя подразделения "
                            + "необходимо выбрать подразделение"
            );
        }

        /*
         * СГМ подразделений не имеет.
         * Пользователь может относиться к СГМ,
         * оставив поле подразделения пустым.
         */
        if ("СГМ".equalsIgnoreCase(service.getCode())
                && unit != null) {

            throw new IllegalArgumentException(
                    "СГМ не содержит подразделений"
            );
        }

        /*
         * Для обычного сотрудника подразделение обязательно,
         * кроме сотрудников СГМ.
         *
         * Если в вашем UserType вместо EMPLOYEE используется
         * другое название, замените EMPLOYEE на значение
         * из вашего enum.
         */
        if (form.getRoleUser() == RoleUser.EXECUTOR
                && unit == null
                ) {

            throw new IllegalArgumentException(
                    "Для сотрудника необходимо "
                            + "выбрать подразделение"
            );
        }

        /*
         * В ранее показанном инициализаторе код
         * технологического отдела был TECHNOLOGY:
         *
         * createIfNotExists(
         *     sdp,
         *     "TECHNOLOGY",
         *     "Технологический отдел"
         * );
         *
         * Поэтому здесь нужно проверять TECHNOLOGY,
         * а не SDP-TECH.
         */
        if (unit != null
                && "TECHNOLOGY".equalsIgnoreCase(unit.getCode())) {

            boolean allowed =
                    form.getRole() == SDP_SERVICE_HEAD_TECHNOLOGY
                           ;

            if (!allowed) {
                throw new IllegalArgumentException(
                        "В технологическом отделе могут работать "
                                + "только технологи и руководитель "
                                + "технологического бюро"
                );
            }

            if (form.getRole()
                    == SDP_SERVICE_HEAD_TECHNOLOGY
                    ) {

                throw new IllegalArgumentException(
                        "Руководитель технологического бюро "
                                + "должен иметь тип "
                                + "«Руководитель подразделения»"
                );
            }
        }

        user.setService(service);
        user.setOrganizationUnit(unit);
        user.setRole(SDP_SERVICE_HEAD_TECHNOLOGY);
    }

    private String normalizeRequired(
            String value,
            String errorMessage
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }

        return value.trim();
    }
}
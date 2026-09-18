package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final String METROLOGY_SERVICE_CODE = "СГМ";

    private final UserRepository userRepository;

    private final OrganizationUnitRepository unitRepository;

    private final ProductionServiceRepository serviceRepository;

    private final PasswordEncoder passwordEncoder;
    private final PasswordVaultService passwordVaultService;
    private final LoginGenerator loginGenerator;

    @Transactional(readOnly = true)
    public List<AdminUserView> findAll() {
        return userRepository
                .findAllByOrderByFullNameAscUsernameAsc()
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

    /**
     * Возвращает пароль пользователя в открытом виде.
     *
     * Значение берётся из password_ciphertext и расшифровывается,
     * поэтому пароль доступен только для учётных записей, созданных
     * после появления хранилища паролей.
     */
    @Transactional(readOnly = true)
    public String revealPassword(Long id) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Пользователь не найден"
                        )
                );

        String ciphertext = user.getPasswordCiphertext();

        if (ciphertext == null || ciphertext.isBlank()) {
            return "Пароль недоступен";
        }

        return passwordVaultService.decrypt(ciphertext);
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

        try {
            userRepository.delete(user);
            userRepository.flush();

        } catch (DataIntegrityViolationException exception) {
            /*
             * На пользователя есть ссылки из заказов, ПЗ или операций,
             * поэтому физическое удаление запрещено ограничениями БД.
             */
            throw new IllegalArgumentException(
                    "Нельзя удалить пользователя: на него есть ссылки. "
                            + "Отключите учётную запись."
            );
        }
    }

    private AdminUserView toView(AppUser user) {
        ProductionService service = user.getService();
        OrganizationUnit unit = user.getOrganizationUnit();

        return new AdminUserView(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getSpecialty(),
                service == null ? null : serviceLabel(service),
                unit == null ? null : unitLabel(unit),
                user.getRoleUser() == null
                        ? null
                        : user.getRoleUser().getDisplayName(),
                user.getRole().getDisplayName(),
                user.isActive()
        );
    }

    private String serviceLabel(ProductionService service) {
        return service.getCode() + " — " + service.getName();
    }

    private String unitLabel(OrganizationUnit unit) {
        return unit.getCode() + " — " + unit.getName();
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

        boolean metrology = isMetrologyService(service);

        /*
         * СГМ подразделения не выбираются,
         * пользователь относится к службе целиком.
         */
        if (metrology && unit != null) {
            throw new IllegalArgumentException(
                    "СГМ не содержит подразделений"
            );
        }

        /*
         * Для исполнителя подразделение обязательно,
         * кроме сотрудников СГМ.
         */
        if (form.getRoleUser() == RoleUser.EXECUTOR
                && unit == null
                && !metrology) {

            throw new IllegalArgumentException(
                    "Для сотрудника необходимо "
                            + "выбрать подразделение"
            );
        }

        /*
         * Технологическое бюро относится к службе СДП,
         * но работать в нём может только роль
         * «Технологическое бюро».
         */
        if (unit != null
                && "TECHNOLOGY".equalsIgnoreCase(unit.getCode())
                && form.getRole()
                != Role.SDP_SERVICE_HEAD_TECHNOLOGY) {

            throw new IllegalArgumentException(
                    "В технологическом бюро может работать "
                            + "только роль «Технологическое бюро»"
            );
        }

        user.setService(service);
        user.setOrganizationUnit(unit);
        user.setRoleUser(form.getRoleUser());
    }

    private boolean isMetrologyService(ProductionService service) {
        return METROLOGY_SERVICE_CODE.equalsIgnoreCase(service.getCode())
                || "SGM".equalsIgnoreCase(service.getCode());
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

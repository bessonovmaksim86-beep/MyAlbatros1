package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.entity.RoleUser;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.PasswordGenerator;
import ru.company.production.service.PasswordVaultService;

/**
 * Создаёт учётную запись начальника технологического бюро,
 * которая должна присутствовать в системе сразу после установки.
 *
 * Пароль генерируется и сохраняется в хранилище паролей,
 * поэтому администратор может посмотреть его на странице
 * «Пользователи».
 */
@Component
@Order(20)
@RequiredArgsConstructor
public class TechnologyHeadInitializer implements CommandLineRunner {

    private static final String USERNAME = "EKorneev";
    private static final String FULL_NAME = "Корнеев Егор Алексеевич";
    private static final String SPECIALTY = "Начальник ТБ";

    private static final String SERVICE_CODE = "SDP";
    private static final String UNIT_CODE = "TECHNOLOGY";

    private final UserRepository userRepository;
    private final ProductionServiceRepository serviceRepository;
    private final OrganizationUnitRepository unitRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordVaultService passwordVaultService;
    private final PasswordGenerator passwordGenerator;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByUsernameIgnoreCase(USERNAME)) {
            return;
        }

        String password = passwordGenerator.generate();

        AppUser user = new AppUser();
        user.setUsername(USERNAME);
        user.setFullName(FULL_NAME);
        user.setSpecialty(SPECIALTY);
        user.setRole(Role.SDP_SERVICE_HEAD_TECHNOLOGY);
        user.setRoleUser(RoleUser.HEAD_OF_WORKSHOP_OR_DEPARTMENT);
        user.setActive(true);

        /*
         * Служба и подразделение берутся из справочника,
         * заполненного миграциями. Если справочник изменён,
         * пользователь создаётся без принадлежности,
         * а не блокирует запуск приложения.
         */
        serviceRepository.findByCodeIgnoreCase(SERVICE_CODE)
                .ifPresent(service -> {
                    user.setService(service);

                    unitRepository.findByCodeIgnoreCase(UNIT_CODE)
                            .filter(unit -> unit.getService() != null
                                    && unit.getService().getId()
                                    .equals(service.getId()))
                            .ifPresent(user::setOrganizationUnit);
                });

        user.setPasswordHash(
                passwordEncoder.encode(password)
        );
        user.setPasswordCiphertext(
                passwordVaultService.encrypt(password)
        );

        userRepository.save(user);
    }
}

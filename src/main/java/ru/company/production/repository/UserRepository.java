package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    /*
     * Учётная запись вместе со службой и подразделением. Модулю цеха
     * границы читает WorkshopStaffService уже после закрытия сессии
     * (open-in-view отключён), поэтому связи нужны загруженными сразу.
     */
    @EntityGraph(
            attributePaths = {
                    "service",
                    "organizationUnit"
            }
    )
    Optional<AppUser> findWithUnitsByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    long countByRole(Role role);

    /**
     * Активных администраторов: отключённая учётная запись доступ
     * к консоли уже не даёт, поэтому считать нужно только действующие.
     */
    long countByRoleAndActiveTrue(Role role);

    /*
     * Служба и подразделение загружаются сразу, потому что
     * open-in-view отключён и страница администраторов
     * отображается уже вне сессии Hibernate.
     */
    @EntityGraph(
            attributePaths = {
                    "service",
                    "organizationUnit"
            }
    )
    List<AppUser> findAllByOrderByFullNameAscUsernameAsc();

    /*
     * Сотрудники подразделения — список цеха для руководителя.
     * ФИО может не заполняться, поэтому сортировка идёт и по логину.
     */
    List<AppUser> findByOrganizationUnitIdAndActiveTrueOrderByFullNameAscUsernameAsc(
            Long organizationUnitId
    );

    /**
     * Сотрудники по роли: резервный список цеха, когда у руководителя
     * подразделение в справочнике не заполнено.
     */
    List<AppUser> findByRoleAndActiveTrueOrderByFullNameAscUsernameAsc(
            Role role
    );

    /*
     * Все действующие сотрудники предприятия: нерабочие дни задаёт
     * администратор разом на всех, поэтому выборка без границы цеха.
     */
    List<AppUser> findByActiveTrueOrderByFullNameAscUsernameAsc();
}

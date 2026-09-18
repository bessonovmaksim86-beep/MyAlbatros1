package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    long countByRole(Role role);

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
}

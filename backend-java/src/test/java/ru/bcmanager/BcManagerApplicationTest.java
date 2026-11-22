package ru.bcmanager;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.Role;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.repository.UserRepository;

/**
 * Slice-тест уровня доступа к данным (H2, профиль {@code test}).
 *
 * <p>Проверяет, что Spring Data JPA репозитории поднимаются и что стратегия
 * наследования {@code SINGLE_TABLE} возвращает правильный полиморфный тип
 * наследника {@code Premises}.
 */
@DataJpaTest
@ActiveProfiles("test")
class JpaRepositoriesSliceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PremisesRepository premisesRepository;

    @Test
    void userIsPersistedAndFoundByUsername() {
        userRepository.save(new User("admin", "{bcrypt}hash", "Тестовый Админ", Role.ADMIN));

        assertThat(userRepository.findByUsername("admin"))
                .isPresent()
                .get()
                .extracting(User::getRole)
                .isEqualTo(Role.ADMIN);
        assertThat(userRepository.existsByUsername("admin")).isTrue();
        assertThat(userRepository.existsByUsername("nobody")).isFalse();
    }

    @Test
    void polymorphicPremisesRoundTrip() {
        java.math.BigDecimal area = new java.math.BigDecimal("45.00");
        java.math.BigDecimal rate = new java.math.BigDecimal("1150.00");
        premisesRepository.save(new OfficeSpace(
                "T-101", "Корпус Т", 1, area, rate, PremisesStatus.AVAILABLE, true));

        assertThat(premisesRepository.findByPremisesType(PremisesType.OFFICE))
                .hasSize(1)
                .first()
                .isInstanceOf(OfficeSpace.class);
        assertThat(premisesRepository.findByStatus(PremisesStatus.AVAILABLE)).hasSize(1);
        assertThat(premisesRepository.findByCode("T-101")).isPresent();
    }
}

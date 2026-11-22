package ru.bcmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.bcmanager.domain.enums.MaintenanceCategory;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.MaintenanceStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.ConferenceRoom;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.ParkingSpot;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.domain.premises.RetailSpace;

/**
 * Unit-тесты полиморфной иерархии помещений и SLA-эскалации заявок.
 *
 * <p>Ожидаемые значения совпадают с {@code shared-fixtures/expected_results.json}
 * (Алгоритмы 3 и 5), чтобы Сессия 5 могла опереться на уже проверенную базу.
 */
class PremisesDomainTest {

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("Офис с мебелью: +10% к типизированной ставке и OpEx 150/м²")
    void officeWithFurniturePricing() {
        Premises office = new OfficeSpace(
                "A-302", "Корпус А", 3, money("45.00"), money("1150.00"),
                PremisesStatus.AVAILABLE, true);

        assertThat(office.getPremisesType()).isEqualTo(PremisesType.OFFICE);
        assertThat(office.calculateBaseMonthlyCost()).isEqualByComparingTo("56925.00");
        assertThat(office.calculateOpexCost()).isEqualByComparingTo("6750.00");
    }

    @Test
    @DisplayName("Торговая площадь с витриной: коэффициент 1.25 и надбавка +15%")
    void retailWithShowcasePricing() {
        Premises retail = new RetailSpace(
                "B-102", "Корпус Б", 1, money("36.40"), money("2200.00"),
                PremisesStatus.AVAILABLE, true);

        assertThat(retail.getPremisesType()).isEqualTo(PremisesType.RETAIL);
        assertThat(retail.calculateBaseMonthlyCost()).isEqualByComparingTo("115115.00");
        assertThat(retail.calculateOpexCost()).isEqualByComparingTo("7280.00");
    }

    @Test
    @DisplayName("Переговорная: коэффициент 1.15 без надбавок и OpEx 100/м²")
    void conferencePricing() {
        Premises room = new ConferenceRoom(
                "C-301", "Корпус В", 3, money("28.00"), money("1500.00"),
                PremisesStatus.AVAILABLE, 12);

        assertThat(room.getPremisesType()).isEqualTo(PremisesType.CONFERENCE);
        assertThat(room.calculateBaseMonthlyCost()).isEqualByComparingTo("48300.00");
        assertThat(room.calculateOpexCost()).isEqualByComparingTo("2800.00");
    }

    @Test
    @DisplayName("Паркинг: крытое место +12%, открытое — без надбавки")
    void parkingPricingDependsOnCoverage() {
        Premises covered = new ParkingSpot(
                "P-02", "Паркинг", -1, money("14.00"), money("900.00"),
                PremisesStatus.AVAILABLE, true);
        Premises open = new ParkingSpot(
                "P-03", "Паркинг", -1, money("14.00"), money("700.00"),
                PremisesStatus.AVAILABLE, false);

        assertThat(covered.calculateBaseMonthlyCost()).isEqualByComparingTo("14112.00");
        assertThat(open.calculateBaseMonthlyCost()).isEqualByComparingTo("9800.00");
        assertThat(covered.calculateOpexCost()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("Заявка HIGH, ожидающая 30 часов, эскалируется до веса 1")
    void highPriorityEscalatesAfterSla() {
        MaintenanceRequest request = new MaintenanceRequest(
                new OfficeSpace("A-301", "Корпус А", 3, money("82.50"), money("1250.00"),
                        PremisesStatus.AVAILABLE, false),
                null,
                "Мигает освещение",
                "Периодически мигает свет.",
                MaintenanceCategory.ELECTRICAL,
                MaintenancePriority.HIGH);
        request.setCreatedAt(LocalDateTime.parse("2026-01-14T06:00:00"));

        LocalDateTime now = LocalDateTime.parse("2026-01-15T12:00:00");

        assertThat(request.calculateEffectivePriorityWeight(now)).isEqualTo(1);
        assertThat(request.isEscalated(now)).isTrue();
    }

    @Test
    @DisplayName("Заявка MEDIUM, ожидающая ровно 48 часов, не эскалируется")
    void mediumPriorityBoundaryIsStrict() {
        MaintenanceRequest request = new MaintenanceRequest(
                new OfficeSpace("A-301", "Корпус А", 3, money("82.50"), money("1250.00"),
                        PremisesStatus.AVAILABLE, false),
                null,
                "Скрип двери",
                "Дверь скрипит.",
                MaintenanceCategory.GENERAL,
                MaintenancePriority.MEDIUM);
        request.setCreatedAt(LocalDateTime.parse("2026-01-13T12:00:00"));
        request.setStatus(MaintenanceStatus.OPEN);

        LocalDateTime now = LocalDateTime.parse("2026-01-15T12:00:00");

        assertThat(request.calculateEffectivePriorityWeight(now)).isEqualTo(3);
        assertThat(request.isEscalated(now)).isFalse();
        assertThat(request.isActive()).isTrue();
    }

    @Test
    @DisplayName("Пересечение интервалов договора: стык дат не считается конфликтом")
    void contractOverlapUsesInclusiveIntervals() {
        Tenant tenant = new Tenant("ООО \"Вектор\"", "7701234567", "Смирнов", "+7", "a@b.ru");
        LeaseContract contract = new LeaseContract(
                "BC-2025-0001",
                new OfficeSpace("A-201", "Корпус А", 2, money("62.50"), money("1200.00"),
                        PremisesStatus.RENTED, true),
                tenant,
                java.time.LocalDate.parse("2025-03-01"),
                java.time.LocalDate.parse("2026-02-28"),
                money("120750.00"),
                ru.bcmanager.domain.enums.ContractStatus.ACTIVE);

        assertThat(contract.overlaps(
                java.time.LocalDate.parse("2026-02-28"),
                java.time.LocalDate.parse("2026-06-30"))).isTrue();
        assertThat(contract.overlaps(
                java.time.LocalDate.parse("2026-03-01"),
                java.time.LocalDate.parse("2026-06-30"))).isFalse();
        assertThat(contract.isConflictRelevant()).isTrue();
    }

    @Test
    @DisplayName("ИНН: валидны только 10 или 12 цифр")
    void innValidation() {
        assertThat(Tenant.isValidInn("7701234567")).isTrue();
        assertThat(Tenant.isValidInn("771234567890")).isTrue();
        assertThat(Tenant.isValidInn("770123456")).isFalse();
        assertThat(Tenant.isValidInn("77012345678A")).isFalse();
        assertThat(Tenant.isValidInn(null)).isFalse();
    }
}

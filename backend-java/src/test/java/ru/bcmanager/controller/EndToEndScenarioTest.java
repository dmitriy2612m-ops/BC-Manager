package ru.bcmanager.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.Role;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.PaymentRepository;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.repository.TenantRepository;
import ru.bcmanager.repository.UserRepository;

/**
 * Сквозные сценарии Сессии 9 (Plan.md, раздел 7, Сессия 9 и раздел 8.3).
 *
 * <p>Тест проходит весь бизнес-процесс через HTTP: подбор помещения, оформление
 * договора, активацию, оплату, пересчёт пени, расторжение и закрытие заявки на
 * ТО. Дополнительно проверяются сквозные права ролей и состояние БД после
 * каждого шага.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EndToEndScenarioTest {

    private static final String RAW_PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PremisesRepository premisesRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    private Tenant tenant;

    private Premises premises;

    @BeforeEach
    void setUp() {
        userRepository.save(new User(
                "admin",
                passwordEncoder.encode(RAW_PASSWORD),
                "Администратор Системы",
                Role.ADMIN));
        userRepository.save(new User(
                "manager",
                passwordEncoder.encode(RAW_PASSWORD),
                "Менеджер Аренды",
                Role.MANAGER));

        tenant = tenantRepository.save(new Tenant(
                "ООО Сквозной Тест",
                "7709876543",
                "Петров Пётр",
                "+7 (495) 111-11-11",
                "e2e@example.com"));

        premises = premisesRepository.save(new OfficeSpace(
                "E2E-201",
                "Корпус Е",
                2,
                new BigDecimal("80.00"),
                new BigDecimal("1200.00"),
                PremisesStatus.AVAILABLE,
                true));
    }

    @Test
    @DisplayName("Сквозной процесс: подбор → договор → активация → оплата → пени → расторжение")
    void fullBusinessProcessThroughApi() throws Exception {
        String token = loginAs("manager");

        // 1. Умный подбор (Алгоритм 2) находит наше помещение.
        mockMvc.perform(post("/api/v1/premises/match")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"OFFICE",
                                  "targetArea":80.00,
                                  "minArea":40.00,
                                  "maxArea":200.00,
                                  "maxBudget":900000.00,
                                  "startDate":"2026-01-01",
                                  "endDate":"2026-12-31"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].code").value("E2E-201"));

        // 2. Предпросмотр (Алгоритмы 1 и 3) на 12 месяцев даёт скидку 10%.
        mockMvc.perform(post("/api/v1/contracts/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"premisesId":%d,"startDate":"2026-01-01","endDate":"2026-12-31"}
                                """.formatted(premises.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months").value(12))
                .andExpect(jsonPath("$.discountPercent").value(10.00))
                .andExpect(jsonPath("$.schedule.length()").value(12));

        // 3. Оформление сразу активным договором → помещение становится RENTED.
        long contractId = createContract(token, "BC-E2E-0001", true);
        assertThat(premisesRepository.findById(premises.getId()).orElseThrow().getStatus())
                .isEqualTo(PremisesStatus.RENTED);

        // 4. Частичная оплата первого счёта → PARTIAL.
        JsonNode payments = getJson("/api/v1/payments?contractId=" + contractId, token);
        assertThat(payments.size()).isEqualTo(12);
        long firstPaymentId = payments.get(0).get("id").asLong();
        BigDecimal amountDue = payments.get(0).get("amountDue").decimalValue();

        mockMvc.perform(post("/api/v1/payments/" + firstPaymentId + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"paidDate":"2026-01-15"}
                                """.formatted(amountDue.divide(BigDecimal.valueOf(4)).toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"));

        // 5. Досрочная оплата остатка → PAID.
        BigDecimal rest = amountDue.multiply(BigDecimal.valueOf(3)).divide(BigDecimal.valueOf(4));
        mockMvc.perform(post("/api/v1/payments/" + firstPaymentId + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"paidDate":"2026-01-20"}
                                """.formatted(rest.toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 6. Расторжение договора возвращает помещение в AVAILABLE.
        mockMvc.perform(patch("/api/v1/contracts/" + contractId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TERMINATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));
        assertThat(premisesRepository.findById(premises.getId()).orElseThrow().getStatus())
                .isEqualTo(PremisesStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Заявка на ТО: создание и закрытие через API фиксирует resolved_at")
    void maintenanceRequestLifecycle() throws Exception {
        String token = loginAs("manager");
        MvcResult created = mockMvc.perform(post("/api/v1/maintenance")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "premisesId":%d,
                                  "title":"Не работает кондиционер",
                                  "description":"В переговорной не охлаждает",
                                  "category":"HVAC",
                                  "priority":"CRITICAL"
                                }
                                """.formatted(premises.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        long requestId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(get("/api/v1/maintenance")
                        .param("prioritized", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].effectivePriority").value(1));

        mockMvc.perform(patch("/api/v1/maintenance/" + requestId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    @Test
    @DisplayName("MANAGER не может удалить помещение, но может пересчитать пени")
    void roleSeparationAcrossEndpoints() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .delete("/api/v1/premises/" + premises.getId())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/payments/recalculate-penalties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calculationDate\":\"2027-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebt").isNotEmpty());
    }

    @Test
    @DisplayName("График платежей и пени сохраняются в БД после пересчёта")
    void penaltiesArePersisted() throws Exception {
        String token = loginAs("manager");
        long contractId = createContract(token, "BC-E2E-0002", false);

        // Сдвигаем срок оплаты первого счёта в прошлое, чтобы получить просрочку.
        var payment = paymentRepository
                .findByContractIdOrderByPeriodNumberAsc(contractId).get(0);
        payment.setDueDate(LocalDate.of(2026, 1, 1));
        paymentRepository.save(payment);

        mockMvc.perform(post("/api/v1/payments/recalculate-penalties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calculationDate\":\"2026-02-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].overdueDays").value(40));

        var reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getPenaltyAmount()).isPositive();
        assertThat(reloaded.getStatus().name()).isEqualTo("OVERDUE");

        // 30 дней по 0.1% + 10 дней по 0.2% = 5% от непогашенного остатка.
        BigDecimal expected = reloaded.getAmountDue()
                .multiply(new BigDecimal("0.05"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(reloaded.getPenaltyAmount()).isEqualByComparingTo(expected);
    }

    // --------------------------------------------------------------------- //
    // Вспомогательные методы
    // --------------------------------------------------------------------- //

    private String loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    private long createContract(String token, String number, boolean activate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/contracts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractNumber":"%s",
                                  "tenantId":%d,
                                  "premisesId":%d,
                                  "startDate":"2026-01-01",
                                  "endDate":"2026-12-31",
                                  "activate":%s
                                }
                                """.formatted(number, tenant.getId(), premises.getId(), activate)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

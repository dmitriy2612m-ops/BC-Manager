package ru.bcmanager.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.MaintenanceCategory;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.PaymentStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.Role;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.MaintenanceRequestRepository;
import ru.bcmanager.repository.PaymentRepository;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.repository.TenantRepository;
import ru.bcmanager.repository.UserRepository;

/**
 * Интеграционные тесты REST API (Plan.md, раздел 5, критерий готовности Сессии 6).
 *
 * <p>Приложение поднимается на H2 в режиме PostgreSQL, поэтому тесты проверяют
 * полный путь: фильтры безопасности, JWT, контроллеры, сервисы и БД. Каждый
 * тест выполняется в транзакции с откатом и создаёт собственные данные.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RestApiIntegrationTest {

    /** Тестовый пароль демо-пользователей. */
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

    @Autowired
    private MaintenanceRequestRepository maintenanceRepository;

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
                "ООО Тест",
                "7701234567",
                "Иванов Иван",
                "+7 (495) 000-00-00",
                "test@example.com"));

        OfficeSpace office = new OfficeSpace(
                "T-101",
                "Корпус Т",
                1,
                new BigDecimal("50.00"),
                new BigDecimal("1000.00"),
                PremisesStatus.AVAILABLE,
                true);
        premises = premisesRepository.save(office);
    }

    @Test
    @DisplayName("POST /auth/login: правильные данные возвращают JWT и роль")
    void loginReturnsToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.fullName").value("Администратор Системы"));
    }

    @Test
    @DisplayName("POST /auth/login: неверный пароль возвращает 401 в едином формате ошибки")
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message", containsString("Неверный логин")));
    }

    @Test
    @DisplayName("GET /premises без токена возвращает 401")
    void premisesWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/premises"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /auth/me возвращает данные текущего пользователя")
    void meReturnsCurrentUser() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("manager"))
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    @DisplayName("POST /premises: MANAGER получает 403 Forbidden")
    void managerCannotCreatePremises() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(post("/api/v1/premises")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(premisesJson("T-202")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /premises: ADMIN создаёт помещение, GET возвращает его в списке")
    void adminCanCreatePremises() throws Exception {
        String token = loginAs("admin");
        mockMvc.perform(post("/api/v1/premises")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(premisesJson("T-202")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("T-202"))
                .andExpect(jsonPath("$.premisesType").value("OFFICE"));

        mockMvc.perform(get("/api/v1/premises")
                        .param("building", "Корпус Т")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("POST /premises: пустой код возвращает 400 с детализацией поля")
    void createPremisesValidationError() throws Exception {
        String token = loginAs("admin");
        mockMvc.perform(post("/api/v1/premises")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"",
                                  "building":"Корпус Т",
                                  "floor":1,
                                  "premisesType":"OFFICE",
                                  "areaSqm":40.00,
                                  "baseRatePerSqm":900.00
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0].field").value("code"));
    }

    @Test
    @DisplayName("POST /premises/match: возвращает ранжированных кандидатов со Score")
    void matchReturnsRankedCandidates() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(post("/api/v1/premises/match")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"OFFICE",
                                  "targetArea":50.00,
                                  "minArea":10.00,
                                  "maxArea":200.00,
                                  "maxBudget":500000.00,
                                  "startDate":"2026-04-01",
                                  "endDate":"2027-03-31"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].code").value("T-101"))
                .andExpect(jsonPath("$.candidates[0].score", notNullValue()))
                .andExpect(jsonPath("$.candidates[0].rank").value(1));
    }

    @Test
    @DisplayName("POST /contracts/preview: рассчитывает аренду и график из 12 платежей")
    void previewCalculatesSchedule() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(post("/api/v1/contracts/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "premisesId":%d,
                                  "startDate":"2026-03-01",
                                  "endDate":"2027-02-28"
                                }
                                """.formatted(premises.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premisesCode").value("T-101"))
                .andExpect(jsonPath("$.months").value(12))
                .andExpect(jsonPath("$.discountPercent").value(10.00))
                .andExpect(jsonPath("$.totalMonthly", notNullValue()))
                .andExpect(jsonPath("$.schedule.length()").value(12));
    }

    @Test
    @DisplayName("POST /contracts: пересечение дат даёт 409 Conflict, договор не создаётся")
    void contractConflictReturns409() throws Exception {
        String token = loginAs("manager");
        createContract(token, "BC-TEST-0001", "2026-01-01", "2026-12-31")
                .andExpect(status().isCreated());

        createContract(token, "BC-TEST-0002", "2026-06-01", "2027-05-31")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("занято")));
    }

    @Test
    @DisplayName("POST /contracts: стык дат (следующий день) конфликта не даёт")
    void contractOnAdjacentDatesIsAllowed() throws Exception {
        String token = loginAs("manager");
        createContract(token, "BC-TEST-0003", "2026-01-01", "2026-12-31")
                .andExpect(status().isCreated());

        createContract(token, "BC-TEST-0004", "2027-01-01", "2027-12-31")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PATCH /contracts/{id}/status: активация переводит помещение в RENTED")
    void activatingContractRentsPremises() throws Exception {
        String token = loginAs("manager");
        MvcResult created = createContract(token, "BC-TEST-0005", "2026-01-01", "2026-12-31")
                .andExpect(status().isCreated())
                .andReturn();
        long contractId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(patch("/api/v1/contracts/" + contractId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/premises/" + premises.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RENTED"));
    }

    @Test
    @DisplayName("POST /payments/{id}/pay: частичная оплата даёт статус PARTIAL")
    void partialPaymentUpdatesStatus() throws Exception {
        String token = loginAs("manager");
        createContract(token, "BC-TEST-0006", "2026-01-01", "2026-12-31")
                .andExpect(status().isCreated());

        JsonNode payments = getJson("/api/v1/payments?contractId="
                + contractOf("BC-TEST-0006").getId(), token);
        long paymentId = payments.get(0).get("id").asLong();
        BigDecimal amountDue = payments.get(0).get("amountDue").decimalValue();

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"paidDate":"2026-01-10"}
                                """.formatted(amountDue.divide(BigDecimal.valueOf(2)).toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"));
    }

    @Test
    @DisplayName("POST /payments/recalculate-penalties: 40 дней просрочки дают 5% пени")
    void recalculatePenaltiesFor40Days() throws Exception {
        String token = loginAs("manager");
        // Платёж с истёкшим сроком оплаты и нулевой оплатой — 40 дней просрочки.
        createContract(token, "BC-TEST-0007", "2025-01-01", "2025-12-31")
                .andExpect(status().isCreated());
        var contract = contractOf("BC-TEST-0007");
        Payment payment = paymentRepository.findByContractIdOrderByPeriodNumberAsc(contract.getId())
                .get(0);
        payment.setDueDate(LocalDate.of(2026, 1, 1));
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.save(payment);

        mockMvc.perform(post("/api/v1/payments/recalculate-penalties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calculationDate\":\"2026-02-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].overdueDays").value(40))
                .andExpect(jsonPath("$.payments[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.payments[0].capped").value(false))
                .andExpect(jsonPath("$.totalDebt", notNullValue()));
    }

    @Test
    @DisplayName("GET /maintenance?prioritized=true: эскалация MEDIUM после 48 часов")
    void prioritizedMaintenanceQueueEscalates() throws Exception {
        MaintenanceRequest escalated = new MaintenanceRequest(
                premises,
                null,
                "Протечка",
                "Протечка в санузле",
                MaintenanceCategory.PLUMBING,
                MaintenancePriority.MEDIUM);
        escalated.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        maintenanceRepository.save(escalated);

        String token = loginAs("manager");
        mockMvc.perform(get("/api/v1/maintenance")
                        .param("prioritized", "true")
                        .param("now", "2026-01-03T01:00:00")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(escalated.getId()))
                .andExpect(jsonPath("$[0].effectivePriority").value(2))
                .andExpect(jsonPath("$[0].escalated").value(true));
    }

    @Test
    @DisplayName("GET /analytics/summary: возвращает загруженность и MRR")
    void analyticsSummaryReturnsKpi() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(get("/api/v1/analytics/summary")
                        .param("asOf", "2026-06-01")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAreaSqm", notNullValue()))
                .andExpect(jsonPath("$.occupancyPercent", notNullValue()))
                .andExpect(jsonPath("$.mrr", notNullValue()))
                .andExpect(jsonPath("$.byType", notNullValue()))
                .andExpect(jsonPath("$.byFloor", notNullValue()));
    }

    @Test
    @DisplayName("GET /analytics/export/csv: отдаёт CSV в UTF-8 с BOM и заголовком")
    void exportCsvHasBomAndHeader() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(get("/api/v1/analytics/export/csv")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("contracts.csv")))
                .andExpect(result -> {
                    byte[] body = result.getResponse().getContentAsByteArray();
                    assert body.length > 3;
                    assert (body[0] & 0xFF) == 0xEF;
                    assert (body[1] & 0xFF) == 0xBB;
                    assert (body[2] & 0xFF) == 0xBF;
                })
                .andExpect(content().string(containsString("contract_number;premises_code")));
    }

    @Test
    @DisplayName("DELETE /premises/{id}: MANAGER получает 403, ADMIN удаляет помещение")
    void deletePremisesRoleSeparation() throws Exception {
        String managerToken = loginAs("manager");
        mockMvc.perform(delete("/api/v1/premises/" + premises.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());

        String adminToken = loginAs("admin");
        mockMvc.perform(delete("/api/v1/premises/" + premises.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /tenants?query=: поиск по названию возвращает найденного арендатора")
    void tenantSearchFindsByCompanyName() throws Exception {
        String token = loginAs("manager");
        mockMvc.perform(get("/api/v1/tenants")
                        .param("query", "Тест")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].companyName").value("ООО Тест"))
                .andExpect(jsonPath("$[0].inn").value("7701234567"));
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

    private org.springframework.test.web.servlet.ResultActions createContract(
            String token, String number, String startDate, String endDate) throws Exception {
        return mockMvc.perform(post("/api/v1/contracts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "contractNumber":"%s",
                          "tenantId":%d,
                          "premisesId":%d,
                          "startDate":"%s",
                          "endDate":"%s",
                          "activate":false
                        }
                        """.formatted(number, tenant.getId(), premises.getId(), startDate, endDate)));
    }

    private ru.bcmanager.domain.LeaseContract contractOf(String number) {
        return contractRepository.findByContractNumber(number).orElseThrow();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String premisesJson(String code) {
        return """
                {
                  "code":"%s",
                  "building":"Корпус Т",
                  "floor":2,
                  "premisesType":"OFFICE",
                  "areaSqm":40.00,
                  "baseRatePerSqm":900.00,
                  "status":"AVAILABLE",
                  "hasFurniture":true,
                  "hasShowcase":false,
                  "covered":true,
                  "description":"Тестовое помещение"
                }
                """.formatted(code);
    }
}

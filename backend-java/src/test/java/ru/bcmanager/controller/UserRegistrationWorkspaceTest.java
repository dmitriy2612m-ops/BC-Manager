package ru.bcmanager.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
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
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.Role;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.MaintenanceRequestRepository;
import ru.bcmanager.repository.PaymentRepository;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.repository.TenantRepository;
import ru.bcmanager.repository.UserRepository;

/**
 * Интеграционные тесты регистрации по логину и паролю и изоляции персональных
 * рабочих пространств пользователей (без {@code @Transactional}, чтобы проверить
 * работу с {@code open-in-view: false}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserRegistrationWorkspaceTest {

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

    private Tenant sharedTenant;

    private OfficeSpace sharedPremises;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(new User(
                "admin", passwordEncoder.encode("secret123"), "Администратор", Role.ADMIN));
        sharedTenant = tenantRepository.save(new Tenant(
                "ООО Демо Компания", "7701234567", "Смирнов А.П.", "+7 (495) 111-22-33", "demo@example.com"));
        sharedPremises = premisesRepository.save(new OfficeSpace(
                "A-101", "Корпус А", 1, new BigDecimal("50.00"), new BigDecimal("1200.00"),
                PremisesStatus.AVAILABLE, true));
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        maintenanceRepository.deleteAll();
        contractRepository.deleteAll();
        premisesRepository.deleteAll();
        tenantRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /auth/register: создаёт пустой аккаунт, изолированный от демо-данных")
    void registeredUserStartsWithEmptyWorkspace() throws Exception {
        String token = registerAndGetToken("user_one", "pass1234");

        mockMvc.perform(get("/api/v1/premises").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/contracts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/maintenance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAreaSqm").value(0))
                .andExpect(jsonPath("$.mrr").value(0));
    }

    @Test
    @DisplayName("Персональные помещения, арендаторы и договоры сохраняются при повторном входе")
    void registeredUserDataPersistsAcrossLogins() throws Exception {
        String registerToken = registerAndGetToken("owner_alpha", "alpha123");

        // Создаём помещение с тем же кодом A-101, что и в общем демо-пространстве.
        MvcResult premisesRes = mockMvc.perform(post("/api/v1/premises")
                        .header("Authorization", "Bearer " + registerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"A-101",
                                  "building":"Мой БЦ",
                                  "floor":2,
                                  "premisesType":"OFFICE",
                                  "areaSqm":60.00,
                                  "baseRatePerSqm":1500.00,
                                  "status":"AVAILABLE",
                                  "hasFurniture":true,
                                  "hasShowcase":false,
                                  "covered":true,
                                  "description":"Личный офис"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long premisesId = objectMapper.readTree(premisesRes.getResponse().getContentAsString())
                .get("id").asLong();

        // Создаём арендатора / компанию с тем же ИНН, что и в общем демо-пространстве.
        MvcResult tenantRes = mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", "Bearer " + registerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyName":"ООО Моя Компания",
                                  "inn":"7701234567",
                                  "contactPerson":"Иванова Ольга",
                                  "phone":"+7 (999) 123-45-67",
                                  "email":"olga@mycompany.ru"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long tenantId = objectMapper.readTree(tenantRes.getResponse().getContentAsString())
                .get("id").asLong();

        // Оформляем договор аренды.
        mockMvc.perform(post("/api/v1/contracts")
                        .header("Authorization", "Bearer " + registerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractNumber":"MY-2026-01",
                                  "tenantId":%d,
                                  "premisesId":%d,
                                  "startDate":"2026-05-01",
                                  "endDate":"2027-04-30",
                                  "activate":true
                                }
                                """.formatted(tenantId, premisesId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractNumber").value("MY-2026-01"));

        // Повторный вход с теми же логином и паролем.
        String reloginToken = loginAndGetToken("owner_alpha", "alpha123");

        mockMvc.perform(get("/api/v1/premises").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].building").value("Мой БЦ"));

        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].companyName").value("ООО Моя Компания"));

        mockMvc.perform(get("/api/v1/tenants/" + tenantId).header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.companyName").value("ООО Моя Компания"))
                .andExpect(jsonPath("$.contracts", hasSize(1)))
                .andExpect(jsonPath("$.contracts[0].contractNumber").value("MY-2026-01"));

        mockMvc.perform(get("/api/v1/contracts").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)));

        // Проверяем, что демо-пользователь admin по-прежнему видит только свои данные.
        String adminToken = loginAndGetToken("admin", "secret123");
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].companyName").value("ООО Демо Компания"));
    }

    @Test
    @DisplayName("Изоляция между пользователями и запрет повторной регистрации логина")
    void usersAreIsolatedFromEachOtherAndDuplicateLoginFails() throws Exception {
        String firstToken = registerAndGetToken("first_user", "pass1234");
        MvcResult createdTenant = mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyName":"ООО Первый",
                                  "inn":"7709998887",
                                  "contactPerson":"Петров И.",
                                  "phone":"+7 (495) 222-33-44",
                                  "email":"first@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long firstTenantId = objectMapper.readTree(createdTenant.getResponse().getContentAsString())
                .get("id").asLong();

        // Повторная регистрация с тем же логином возвращает 409 Conflict.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"FIRST_USER","password":"otherpassword"}
                                """))
                .andExpect(status().isConflict());

        // Второй пользователь не видит данные первого и демо-данные.
        String secondToken = registerAndGetToken("second_user", "pass5678");
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/tenants/" + firstTenantId).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tenants/" + sharedTenant.getId()).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/premises/" + sharedPremises.getId()).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());
    }

    private String registerAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }
}

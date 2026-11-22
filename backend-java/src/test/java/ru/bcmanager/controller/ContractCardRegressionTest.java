package ru.bcmanager.controller;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
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
 * Регрессия: карточка договора {@code GET /contracts/{id}} без внешней транзакции.
 *
 * <p>Тест намеренно <b>не</b> помечен {@code @Transactional}: при
 * {@code open-in-view: false} ленивое поле {@code createdBy} вызывало
 * {@code LazyInitializationException} и ответ 500 на боевом сервере, а
 * транзакционные тесты этого не замечали.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractCardRegressionTest {

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
        cleanUp();
        userRepository.save(new User(
                "admin", passwordEncoder.encode("secret123"), "Администратор Системы", Role.ADMIN));
        tenant = tenantRepository.save(new Tenant(
                "ООО Регресс", "7709999999", "Петров Пётр", "+7 (495) 111-11-11", "r@example.com"));
        premises = premisesRepository.save(new OfficeSpace(
                "R-101", "Корпус R", 1, new BigDecimal("40.00"), new BigDecimal("1000.00"),
                PremisesStatus.AVAILABLE, true));
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        contractRepository.deleteAll();
        premisesRepository.deleteAll();
        tenantRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /contracts/{id}: карточка открывается и показывает автора договора")
    void contractCardLoadsWithAuthor() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("token").asText();

        MvcResult created = mockMvc.perform(post("/api/v1/contracts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractNumber":"REG-0001","tenantId":%d,"premisesId":%d,
                                 "startDate":"2030-01-01","endDate":"2030-12-31","activate":true}
                                """.formatted(tenant.getId(), premises.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        long contractId = body.get("id").asLong();

        mockMvc.perform(get("/api/v1/contracts/" + contractId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.contractNumber").value("REG-0001"))
                .andExpect(jsonPath("$.createdBy").value("Администратор Системы"))
                .andExpect(jsonPath("$.payments.length()").value(12));
    }
}

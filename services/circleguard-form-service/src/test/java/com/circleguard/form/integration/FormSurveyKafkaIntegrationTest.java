package com.circleguard.form.integration;

import com.circleguard.form.FormApplication;
import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Question;
import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.model.QuestionType;
import com.circleguard.form.repository.QuestionnaireRepository;
import com.circleguard.form.service.HealthSurveyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Integration test: persisted survey submission triggers Kafka publish (form → broker contract).
 */
@SpringBootTest(classes = FormApplication.class)
@Testcontainers
@Tag("integration")
class FormSurveyKafkaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private HealthSurveyService healthSurveyService;

    @Autowired
    private QuestionnaireRepository questionnaireRepository;

    @SpyBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    private Questionnaire activeForm;

    @BeforeEach
    void seedQuestionnaire() {
        Question q = Question.builder()
                .text("Do you have fever today?")
                .type(QuestionType.YES_NO)
                .orderIndex(0)
                .build();
        activeForm = Questionnaire.builder()
                .title("Campus intake")
                .version(1)
                .isActive(true)
                .questions(List.of(q))
                .build();
        q.setQuestionnaire(activeForm);
        activeForm = questionnaireRepository.save(activeForm);
    }

    @Test
    void submitSurvey_dispatchesSurveySubmittedEvent() {
        UUID qid = activeForm.getQuestions().get(0).getId();
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(UUID.randomUUID())
                .responses(Map.of(qid.toString(), "YES"))
                .build();

        healthSurveyService.submitSurvey(survey);

        verify(kafkaTemplate, timeout(10_000)).send(eq("survey.submitted"), any(String.class), any());
    }
}

package com.circleguard.form.service;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Question;
import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.model.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for symptom detection rules used before emitting Kafka survey events.
 */
class SymptomMapperBusinessRulesTest {

    private SymptomMapper symptomMapper;
    private Questionnaire questionnaire;

    @BeforeEach
    void setUp() {
        symptomMapper = new SymptomMapper();
        UUID qid = UUID.randomUUID();
        Question fever = Question.builder()
                .id(qid)
                .text("Do you have fever or chills?")
                .type(QuestionType.YES_NO)
                .orderIndex(0)
                .build();
        questionnaire = Questionnaire.builder()
                .id(UUID.randomUUID())
                .title("Daily")
                .version(1)
                .isActive(true)
                .questions(List.of(fever))
                .build();
        fever.setQuestionnaire(questionnaire);
    }

    @Test
    void hasSymptoms_whenFeverQuestionAnsweredYes_returnsTrue() {
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(UUID.randomUUID())
                .responses(Map.of(questionnaire.getQuestions().get(0).getId().toString(), "YES"))
                .build();

        assertTrue(symptomMapper.hasSymptoms(survey, questionnaire));
    }

    @Test
    void hasSymptoms_whenNoMatchingKeywords_returnsFalse() {
        Question other = Question.builder()
                .id(UUID.randomUUID())
                .text("Unrelated question without symptom keywords")
                .type(QuestionType.YES_NO)
                .orderIndex(0)
                .build();
        Questionnaire q = Questionnaire.builder()
                .id(UUID.randomUUID())
                .title("Alt")
                .version(1)
                .isActive(true)
                .questions(List.of(other))
                .build();
        other.setQuestionnaire(q);

        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(UUID.randomUUID())
                .responses(Map.of(other.getId().toString(), "YES"))
                .build();

        assertFalse(symptomMapper.hasSymptoms(survey, q));
    }

    @Test
    void hasSymptoms_whenResponsesNull_returnsFalse() {
        HealthSurvey survey = HealthSurvey.builder().anonymousId(UUID.randomUUID()).responses(null).build();
        assertFalse(symptomMapper.hasSymptoms(survey, questionnaire));
    }

    @Test
    void hasSymptoms_whenQuestionnaireNull_returnsFalse() {
        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(UUID.randomUUID())
                .responses(Map.of("x", "YES"))
                .build();
        assertFalse(symptomMapper.hasSymptoms(survey, null));
    }

    @Test
    void hasSymptoms_whenCoughKeywordAndYes_returnsTrue() {
        Question cough = Question.builder()
                .id(UUID.randomUUID())
                .text("Persistent cough?")
                .type(QuestionType.YES_NO)
                .orderIndex(0)
                .build();
        Questionnaire q = Questionnaire.builder()
                .id(UUID.randomUUID())
                .title("v2")
                .version(2)
                .isActive(true)
                .questions(List.of(cough))
                .build();
        cough.setQuestionnaire(q);

        HealthSurvey survey = HealthSurvey.builder()
                .anonymousId(UUID.randomUUID())
                .responses(Map.of(cough.getId().toString(), "YES"))
                .build();

        assertTrue(symptomMapper.hasSymptoms(survey, q));
    }
}

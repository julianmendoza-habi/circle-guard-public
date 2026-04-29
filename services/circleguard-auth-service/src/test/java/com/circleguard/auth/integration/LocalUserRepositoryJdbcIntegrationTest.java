package com.circleguard.auth.integration;

import com.circleguard.auth.AuthRepositoryTestApplication;
import com.circleguard.auth.model.LocalUser;
import com.circleguard.auth.repository.LocalUserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: local user repository against PostgreSQL (auth ↔ database).
 */
@SpringBootTest(classes = AuthRepositoryTestApplication.class)
@Testcontainers
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class LocalUserRepositoryJdbcIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private LocalUserRepository localUserRepository;

    @Test
    void saveAndFind_byUsername_roundTrips() {
        LocalUser user = new LocalUser();
        user.setUsername("integration-local-user");
        user.setPassword("{noop}secret");
        user.setEmail("integration@test.edu");
        user.setIsActive(true);

        localUserRepository.save(user);

        Optional<LocalUser> found = localUserRepository.findByUsername("integration-local-user");
        assertTrue(found.isPresent());
        assertEquals("integration@test.edu", found.get().getEmail());
    }
}

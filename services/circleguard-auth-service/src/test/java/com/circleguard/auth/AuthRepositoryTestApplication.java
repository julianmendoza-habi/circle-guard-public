package com.circleguard.auth;

import com.circleguard.auth.model.LocalUser;
import com.circleguard.auth.repository.LocalUserRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal auto-configuration slice for repository integration tests (no LDAP / security).
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan(basePackageClasses = LocalUser.class)
@EnableJpaRepositories(basePackageClasses = LocalUserRepository.class)
public class AuthRepositoryTestApplication {
}

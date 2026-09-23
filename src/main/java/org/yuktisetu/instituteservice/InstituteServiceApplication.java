package org.yuktisetu.instituteservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Owns the institute context: trusts, colleges and departments.
 *
 * Split out of admin-service, which after Phase 2 held five DAL modules and no
 * clear ownership of any of them. This service scans exactly one context plus
 * the outbox, which is what lets it own yuktisetu_institute outright.
 *
 * It keeps the /api/admin context path on purpose. The admin portal has ~49
 * hardcoded path literals across two api modules, and the migration's rule is
 * that no frontend code changes -- routing is handled by the Vite proxy, which
 * sends /api/admin/{trusts,colleges,departments} here and the rest elsewhere.
 *
 * It reads the SHARED database: institute was not split off into its own, so
 * admin-service can still join colleges and departments directly.
 */
@SpringBootApplication
@EntityScan(basePackages = "org.yuktisetu.institute.db")
@EnableJpaRepositories(basePackages = "org.yuktisetu.institute.repository")
@ComponentScan({"org.yuktisetu.instituteservice", "org.yuktisetu.core"})
@EnableMethodSecurity
@EnableAsync
public class InstituteServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InstituteServiceApplication.class, args);
    }
}

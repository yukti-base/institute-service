package org.yuktisetu.instituteservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.yuktisetu.institute.db.Degree;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.DegreeRepository;

import java.util.List;

/**
 * Makes sure the standard degree catalogue exists. Seeds only what is MISSING and
 * never touches an existing row, so renaming or deactivating a degree sticks.
 *
 * Exists because a database rebuilt from a schema-only dump comes back with the
 * degrees table empty, and with no degrees nobody can create a college, a
 * department or a student.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DegreeSeeder implements ApplicationRunner {

    private record Seed(String code, String name) {}

    private static final List<Seed> STANDARD = List.of(
            new Seed("BTECH", "B.Tech"),
            new Seed("MTECH", "M.Tech"),
            new Seed("MCA", "MCA"),
            new Seed("BCA", "BCA"),
            new Seed("MBA", "MBA"),
            new Seed("DIPLOMA", "Diploma"));

    private final DegreeRepository degreeRepository;

    @Override
    public void run(ApplicationArguments args) {
        int added = 0;
        for (Seed seed : STANDARD) {
            if (degreeRepository.existsByCodeIgnoreCase(seed.code()) || degreeRepository.existsByNameIgnoreCase(seed.name())) {
                continue;
            }
            Degree d = new Degree();
            d.setCode(seed.code());
            d.setName(seed.name());
            d.setStatus(TenantStatus.ACTIVE);
            degreeRepository.save(d);
            added++;
        }
        if (added > 0) {
            log.info("Seeded {} standard degree(s)", added);
        }
    }
}

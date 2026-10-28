package org.yuktisetu.instituteservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.institute.db.Degree;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.DegreeRepository;
import org.yuktisetu.instituteservice.dto.DegreePatchRequest;
import org.yuktisetu.instituteservice.dto.DegreeRequest;
import org.yuktisetu.instituteservice.dto.DegreeResponse;
import org.yuktisetu.instituteservice.exception.InstituteExceptions;
import org.yuktisetu.instituteservice.policy.InstituteHierarchyPolicy;

import java.util.List;
import java.util.Locale;

/**
 * The trust-wide degree catalogue (B.Tech, M.Tech, MCA, ...).
 *
 * Deliberately no delete: students, departments and drive targets all point at a
 * degree, and a degree that merely stops being offered is INACTIVE, which hides it
 * from pickers without orphaning anything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegreeService {

    private final DegreeRepository degreeRepository;
    private final InstituteHierarchyPolicy policy;
    private final InstituteDependencyGuard dependencyGuard;

    @Transactional(readOnly = true)
    public List<DegreeResponse> list() {
        return degreeRepository.findByIsDeletedFalseOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public DegreeResponse create(UserPrincipal actor, DegreeRequest req) {
        requireManager(actor);
        String code = normaliseCode(req.code());
        String name = req.name().trim();
        if (degreeRepository.existsByCodeIgnoreCase(code)) {
            throw new InstituteExceptions.DuplicateCodeException("Degree", code);
        }
        if (degreeRepository.existsByNameIgnoreCase(name)) {
            throw new InstituteExceptions.DuplicateCodeException("Degree", name);
        }
        Degree degree = new Degree();
        degree.setCode(code);
        degree.setName(name);
        degree.setStatus(TenantStatus.ACTIVE);
        degree.setCreatedBy(actor.userId());
        degree.setUpdatedBy(actor.userId());
        Degree saved = degreeRepository.save(degree);
        log.info("Created degree id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public DegreeResponse patch(UserPrincipal actor, Long id, DegreePatchRequest req) {
        requireManager(actor);
        Degree degree = degreeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Degree", id));
        if (req.code() != null) {
            String code = normaliseCode(req.code());
            if (!code.equalsIgnoreCase(degree.getCode()) && degreeRepository.existsByCodeIgnoreCase(code)) {
                throw new InstituteExceptions.DuplicateCodeException("Degree", code);
            }
            degree.setCode(code);
        }
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("Degree name cannot be blank.");
            }
            if (!name.equalsIgnoreCase(degree.getName()) && degreeRepository.existsByNameIgnoreCase(name)) {
                throw new InstituteExceptions.DuplicateCodeException("Degree", name);
            }
            degree.setName(name);
        }
        degree.setUpdatedBy(actor.userId());
        Degree saved = degreeRepository.save(degree);
        log.info("Patched degree id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public DegreeResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        requireManager(actor);
        Degree degree = degreeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Degree", id));
        if (status == TenantStatus.INACTIVE) {
            var blockers = dependencyGuard.forDegree(id);
            if (!blockers.isEmpty()) {
                throw new InstituteExceptions.StillReferencedException(
                        "Degree", degree.getName(), InstituteDependencyGuard.describe(blockers));
            }
        }
        degree.setStatus(status);
        degree.setUpdatedBy(actor.userId());
        Degree saved = degreeRepository.save(degree);
        log.info("Degree id={} status changed to={} actorId={}", saved.getId(), status, actor.userId());
        return toResponse(saved);
    }

    private void requireManager(UserPrincipal actor) {
        if (!policy.canManageCollege(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
    }

    private static String normaliseCode(String raw) {
        String code = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (code.isEmpty()) {
            throw new InstituteExceptions.InvalidRequestException("Degree code needs letters or digits, e.g. BTECH.");
        }
        return code;
    }

    DegreeResponse toResponse(Degree d) {
        return new DegreeResponse(d.getId(), d.getCode(), d.getName(), d.getStatus(), d.getCreatedAt(), d.getUpdatedAt());
    }
}

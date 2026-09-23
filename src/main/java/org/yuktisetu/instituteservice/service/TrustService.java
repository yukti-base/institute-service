package org.yuktisetu.instituteservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.instituteservice.dto.TrustPatchRequest;
import org.yuktisetu.instituteservice.dto.TrustRequest;
import org.yuktisetu.instituteservice.dto.TrustResponse;
import org.yuktisetu.instituteservice.exception.InstituteExceptions;
import org.yuktisetu.instituteservice.policy.InstituteHierarchyPolicy;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.institute.db.Trust;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.institute.repository.TrustRepository;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrustService {

    private final TrustRepository trustRepository;
    private final CollegeRepository collegeRepository;
    private final InstituteHierarchyPolicy policy;
    private final InstituteDependencyGuard dependencyGuard;

    @Transactional
    public TrustResponse create(UserPrincipal actor, TrustRequest req) {
        if (!policy.canManageTrust(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        if (req.name().isBlank() || req.code().isBlank()) {
            throw new InstituteExceptions.InvalidRequestException("Trust name and code cannot be blank");
        }

        String code = req.code().trim();
        if (trustRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
            throw new InstituteExceptions.DuplicateCodeException("Trust", code);
        }

        Trust trust = new Trust();
        trust.setName(req.name().trim());
        trust.setCode(code);
        trust.setLogoUrl(req.logoUrl());
        trust.setPrimaryContactEmail(req.primaryContactEmail());
        trust.setPrimaryContactPhone(req.primaryContactPhone());
        trust.setStatus(TenantStatus.ACTIVE);
        trust.setCreatedBy(actor.userId());
        trust.setUpdatedBy(actor.userId());

        Trust saved = trustRepository.save(trust);
        log.info("Created trust id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    public List<TrustResponse> listActive() {
        return trustRepository.findByIsDeletedFalse().stream().map(this::toResponse).toList();
    }

    /** The restore screen. Soft-deleted rows are invisible everywhere else. */
    public List<TrustResponse> listDeleted() {
        return trustRepository.findByIsDeletedTrue().stream().map(this::toResponse).toList();
    }

    public TrustResponse get(Long id) {
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));
        return toResponse(trust);
    }

    @Transactional
    public TrustResponse update(UserPrincipal actor, Long id, TrustRequest req) {
        if (!policy.canManageTrust(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));

        String code = req.code().trim();
        if (!code.equalsIgnoreCase(trust.getCode())
                && trustRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
            throw new InstituteExceptions.DuplicateCodeException("Trust", code);
        }

        trust.setName(req.name().trim());
        trust.setCode(code);
        trust.setLogoUrl(req.logoUrl());
        trust.setPrimaryContactEmail(req.primaryContactEmail());
        trust.setPrimaryContactPhone(req.primaryContactPhone());
        trust.setUpdatedBy(actor.userId());

        Trust saved = trustRepository.save(trust);
        log.info("Updated trust id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    /**
     * Partial update: only the fields actually present are touched. The code
     * uniqueness check runs only when a new code was sent, so re-sending the
     * current code is not a conflict with itself.
     */
    @Transactional
    public TrustResponse patch(UserPrincipal actor, Long id, TrustPatchRequest req) {
        if (!policy.canManageTrust(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));

        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("Trust name cannot be blank.");
            }
            trust.setName(name);
        }
        if (req.code() != null) {
            String code = req.code().trim();
            if (code.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("Trust code cannot be blank.");
            }
            if (!code.equalsIgnoreCase(trust.getCode())
                    && trustRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
                throw new InstituteExceptions.DuplicateCodeException("Trust", code);
            }
            trust.setCode(code);
        }
        if (req.logoUrl() != null)              trust.setLogoUrl(blankToNull(req.logoUrl()));
        if (req.primaryContactEmail() != null)  trust.setPrimaryContactEmail(blankToNull(req.primaryContactEmail()));
        if (req.primaryContactPhone() != null)  trust.setPrimaryContactPhone(blankToNull(req.primaryContactPhone()));

        trust.setUpdatedBy(actor.userId());
        Trust saved = trustRepository.save(trust);
        log.info("Patched trust id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    /** An explicitly sent empty string clears an optional field; null never reaches here. */
    private static String blankToNull(String v) {
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    // Status toggle -- a reversible pause, NOT the soft-delete/restore flow.
    // Kept as one method with a target status rather than separate
    // activate()/deactivate() to avoid duplicating the same guard twice.
    @Transactional
    public TrustResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        if (!policy.canManageTrust(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));
        trust.setStatus(status);
        trust.setUpdatedBy(actor.userId());
        Trust saved = trustRepository.save(trust);
        log.info("Trust id={} status changed to={} actorId={}", saved.getId(), status, actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) { // same authority tier as restore -- IT_ADMIN/TNP_SUPER_ADMIN
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));

        // Refuse rather than orphan. A trust deleted out from under its colleges
        // leaves them unreachable from every listing that walks the hierarchy
        // top-down, with no error anywhere to explain it.
        var blockers = dependencyGuard.forTrust(id);
        if (!blockers.isEmpty()) {
            log.warn("Refused soft-delete of trust id={} -- still referenced by {}", id, blockers);
            throw new InstituteExceptions.StillReferencedException(
                    "Trust", trust.getName(), InstituteDependencyGuard.describe(blockers));
        }

        trust.setDeleted(true);
        trust.setDeletedAt(Instant.now());
        trust.setDeletedBy(actor.userId());
        trustRepository.save(trust);
        log.info("Soft-deleted trust id={} code={} actorId={}", trust.getId(), trust.getCode(), actor.userId());
    }

    @Transactional
    public TrustResponse restore(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findById(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));
        trust.setDeleted(false);
        trust.setDeletedAt(null);
        trust.setDeletedBy(null);
        trust.setUpdatedBy(actor.userId());
        Trust saved = trustRepository.save(trust);
        log.info("Restored trust id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findById(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", id));
        if (!trust.isDeleted()) {
            throw new InstituteExceptions.NotYetDeactivatedException();
        }
        // Guard: refuse if ANY College row -- soft-deleted or not -- still
        // references this Trust. See CollegeRepository_ADDITIONS.java.
        if (collegeRepository.existsByTrustId(id)) {
            throw new InstituteExceptions.HasDependentRecordsException("Trust", id, "College");
        }
        trustRepository.delete(trust);
        log.info("Hard-deleted trust id={} code={} actorId={}", trust.getId(), trust.getCode(), actor.userId());
    }

    private TrustResponse toResponse(Trust t) {
        return new TrustResponse(
                t.getId(), t.getName(), t.getCode(), t.getLogoUrl(),
                t.getPrimaryContactEmail(), t.getPrimaryContactPhone(),
                t.getStatus(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}

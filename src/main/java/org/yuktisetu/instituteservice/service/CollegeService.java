package org.yuktisetu.instituteservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.instituteservice.dto.CollegePatchRequest;
import org.yuktisetu.instituteservice.dto.CollegeRequest;
import org.yuktisetu.instituteservice.dto.CollegeResponse;
import org.yuktisetu.instituteservice.exception.InstituteExceptions;
import org.yuktisetu.instituteservice.policy.InstituteHierarchyPolicy;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.institute.db.Trust;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.institute.db.Degree;
import org.yuktisetu.institute.repository.DegreeRepository;
import org.yuktisetu.institute.repository.DepartmentRepository;
import org.yuktisetu.instituteservice.dto.CollegeDegreesRequest;
import org.yuktisetu.instituteservice.dto.DegreeSummary;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.yuktisetu.institute.repository.TrustRepository;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollegeService {

    private final CollegeRepository collegeRepository;
    private final TrustRepository trustRepository;
    private final DepartmentRepository departmentRepository;
    private final DegreeRepository degreeRepository;
    private final InstituteHierarchyPolicy policy;
    private final InstituteDependencyGuard dependencyGuard;

    @Transactional
    public CollegeResponse create(UserPrincipal actor, CollegeRequest req) {
        if (!policy.canManageCollege(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        Trust trust = trustRepository.findByIdAndIsDeletedFalse(req.trustId())
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Trust", req.trustId()));
        if (trust.getStatus() != TenantStatus.ACTIVE) {
            throw new InstituteExceptions.ParentNotActiveException("Trust", trust.getId());
        }

        String code = req.code().trim();
        if (collegeRepository.existsByTrustIdAndCodeIgnoreCaseAndIsDeletedFalse(trust.getId(), code)) {
            throw new InstituteExceptions.DuplicateCodeException("College", code);
        }

        College college = new College();
        college.setTrust(trust);
        college.setName(req.name().trim());
        college.setCode(code);
        college.setLogoUrl(req.logoUrl());
        college.setAddress(req.address());
        college.setPrimaryContactName(req.primaryContactName().trim());
        college.setPrimaryContactEmail(req.primaryContactEmail());
        college.setPrimaryContactPhone(req.primaryContactPhone());
        college.setStatus(TenantStatus.ACTIVE);
        college.setDegrees(resolveDegrees(req.degreeIds()));
        college.setCreatedBy(actor.userId());
        college.setUpdatedBy(actor.userId());

        // NOTE: SSOT Section 8's onboarding walkthrough has this step
        // auto-emailing a welcome message with login details to
        // primaryContactEmail once the college is created. That's the
        // Notification Service's job, not admin-service's -- wire an event
        // publish (Kafka, per the architecture doc) here once that service
        // exists. Deliberately left undone rather than faked with a direct
        // SMTP call from inside this transaction.
        College saved = collegeRepository.save(college);
        log.info("Created college id={} code={} trustId={} actorId={}", saved.getId(), saved.getCode(), trust.getId(), actor.userId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CollegeResponse> listActiveForTrust(Long trustId) {
        return collegeRepository.findByTrustIdAndIsDeletedFalse(trustId).stream().map(this::toResponse).toList();
    }

    /** The restore screen, scoped to one trust. */
    @Transactional(readOnly = true)
    public List<CollegeResponse> listDeletedForTrust(Long trustId) {
        return collegeRepository.findByTrustIdAndIsDeletedTrue(trustId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CollegeResponse get(Long id) {
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));
        return toResponse(college);
    }

    @Transactional
    public CollegeResponse update(UserPrincipal actor, Long id, CollegeRequest req) {
        if (!policy.canManageCollege(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));

        String code = req.code().trim();
        if (!code.equalsIgnoreCase(college.getCode())
                && collegeRepository.existsByTrustIdAndCodeIgnoreCaseAndIsDeletedFalse(college.getTrust().getId(), code)) {
            throw new InstituteExceptions.DuplicateCodeException("College", code);
        }

        college.setName(req.name().trim());
        college.setCode(code);
        college.setLogoUrl(req.logoUrl());
        college.setAddress(req.address());
        college.setPrimaryContactName(req.primaryContactName().trim());
        college.setPrimaryContactEmail(req.primaryContactEmail());
        college.setPrimaryContactPhone(req.primaryContactPhone());
        applyDegrees(college, req.degreeIds());
        college.setUpdatedBy(actor.userId());

        College saved = collegeRepository.save(college);
        log.info("Updated college id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    /** Partial update; only fields actually present are touched. See TrustService.patch. */
    @Transactional
    public CollegeResponse patch(UserPrincipal actor, Long id, CollegePatchRequest req) {
        if (!policy.canManageCollege(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));

        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("College name cannot be blank.");
            }
            college.setName(name);
        }
        if (req.code() != null) {
            String code = req.code().trim();
            if (code.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("College code cannot be blank.");
            }
            // Codes are unique per trust, not globally -- scope the check the
            // same way create() does or two trusts could never share "CSE".
            if (!code.equalsIgnoreCase(college.getCode())
                    && collegeRepository.existsByTrustIdAndCodeIgnoreCaseAndIsDeletedFalse(
                            college.getTrust().getId(), code)) {
                throw new InstituteExceptions.DuplicateCodeException("College", code);
            }
            college.setCode(code);
        }
        if (req.logoUrl() != null)              college.setLogoUrl(blankToNull(req.logoUrl()));
        if (req.address() != null)              college.setAddress(blankToNull(req.address()));
        if (req.primaryContactName() != null)   college.setPrimaryContactName(blankToNull(req.primaryContactName()));
        if (req.primaryContactEmail() != null)  college.setPrimaryContactEmail(blankToNull(req.primaryContactEmail()));
        if (req.primaryContactPhone() != null)  college.setPrimaryContactPhone(blankToNull(req.primaryContactPhone()));

        college.setUpdatedBy(actor.userId());
        College saved = collegeRepository.save(college);
        log.info("Patched college id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    private static String blankToNull(String v) {
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    @Transactional
    public CollegeResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        if (!policy.canManageCollege(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));
        college.setStatus(status);
        college.setUpdatedBy(actor.userId());
        College saved = collegeRepository.save(college);
        log.info("College id={} status changed to={} actorId={}", saved.getId(), status, actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));

        // Refuse rather than orphan -- departments, staff assignments, drive
        // targets and offers all hang off this row and none of them would
        // report an error once it disappeared from the listings.
        var blockers = dependencyGuard.forCollege(id);
        if (!blockers.isEmpty()) {
            log.warn("Refused soft-delete of college id={} -- still referenced by {}", id, blockers);
            throw new InstituteExceptions.StillReferencedException(
                    "College", college.getName(), InstituteDependencyGuard.describe(blockers));
        }

        college.setDeleted(true);
        college.setDeletedAt(Instant.now());
        college.setDeletedBy(actor.userId());
        collegeRepository.save(college);
        log.info("Soft-deleted college id={} code={} actorId={}", college.getId(), college.getCode(), actor.userId());
    }

    @Transactional
    public CollegeResponse restore(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findById(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));
        college.setDeleted(false);
        college.setDeletedAt(null);
        college.setDeletedBy(null);
        college.setUpdatedBy(actor.userId());
        College saved = collegeRepository.save(college);
        log.info("Restored college id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findById(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));
        if (!college.isDeleted()) {
            throw new InstituteExceptions.NotYetDeactivatedException();
        }
        // Guard: refuse if ANY Department row -- soft-deleted or not -- still
        // references this College. See DepartmentRepository_ADDITIONS.java.
        if (departmentRepository.existsByCollegeId(id)) {
            throw new InstituteExceptions.HasDependentRecordsException("College", id, "Department");
        }
        collegeRepository.delete(college);
        log.info("Hard-deleted college id={} code={} actorId={}", college.getId(), college.getCode(), actor.userId());
    }

    /**
     * Replaces the set of degrees a college offers.
     *
     * A degree cannot be dropped while anything at this college still uses it --
     * a department of that degree, a student, or a drive target -- because that
     * would leave a department whose degree its own college does not offer.
     */
    @Transactional
    public CollegeResponse setDegrees(UserPrincipal actor, Long id, CollegeDegreesRequest req) {
        if (!policy.canManageCollege(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", id));

        applyDegrees(college, req.degreeIds());
        college.setUpdatedBy(actor.userId());
        College saved = collegeRepository.save(college);
        log.info("Set degrees of college id={} actorId={}", id, actor.userId());
        return toResponse(saved);
    }

    /** Swaps the college's degrees, refusing to drop one that is still in use at this college. */
    private void applyDegrees(College college, List<Long> degreeIds) {
        Long id = college.getId();
        Set<Degree> next = resolveDegrees(degreeIds);
        Set<Long> nextIds = next.stream().map(Degree::getId).collect(Collectors.toSet());
        for (Degree current : college.getDegrees()) {
            if (!nextIds.contains(current.getId())) {
                var blockers = dependencyGuard.forCollegeDegree(id, current.getId());
                if (!blockers.isEmpty()) {
                    throw new InstituteExceptions.StillReferencedException(
                            "Degree " + current.getName() + " at " + college.getName(), current.getName(),
                            InstituteDependencyGuard.describe(blockers));
                }
            }
        }

        college.getDegrees().clear();
        college.getDegrees().addAll(next);
    }

    /** Every id must be a live, ACTIVE degree; one bad id rejects the lot rather than silently dropping it. */
    private Set<Degree> resolveDegrees(List<Long> degreeIds) {
        if (degreeIds == null || degreeIds.isEmpty()) {
            throw new InstituteExceptions.InvalidRequestException("A college must offer at least one degree.");
        }
        Set<Long> wanted = new LinkedHashSet<>(degreeIds);
        List<Degree> found = degreeRepository.findByIdInAndIsDeletedFalse(wanted);
        if (found.size() != wanted.size()
                || found.stream().anyMatch(d -> d.getStatus() != TenantStatus.ACTIVE)) {
            throw new InstituteExceptions.InvalidRequestException(
                    "One or more selected degrees do not exist or are inactive.");
        }
        return new LinkedHashSet<>(found);
    }

    private CollegeResponse toResponse(College c) {
        return new CollegeResponse(
                c.getId(), c.getTrust().getId(), c.getName(), c.getCode(), c.getLogoUrl(),
                c.getAddress(), c.getPrimaryContactName(), c.getPrimaryContactEmail(),
                c.getPrimaryContactPhone(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getDegrees().stream()
                        .sorted(java.util.Comparator.comparing(Degree::getName))
                        .map(d -> new DegreeSummary(d.getId(), d.getCode(), d.getName()))
                        .toList()
        );
    }
}

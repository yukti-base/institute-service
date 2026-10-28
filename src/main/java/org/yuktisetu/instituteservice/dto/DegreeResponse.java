package org.yuktisetu.instituteservice.dto;

import org.yuktisetu.institute.model.TenantStatus;

import java.time.Instant;

public record DegreeResponse(
        Long id,
        String code,
        String name,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}

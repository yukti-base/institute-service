package org.yuktisetu.instituteservice.dto;

import org.yuktisetu.institute.model.TenantStatus;

import java.time.Instant;

public record TrustResponse(
        Long id,
        String name,
        String code,
        String logoUrl,
        String primaryContactEmail,
        String primaryContactPhone,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}

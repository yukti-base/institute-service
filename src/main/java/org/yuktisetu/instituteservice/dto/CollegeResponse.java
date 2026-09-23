package org.yuktisetu.instituteservice.dto;

import org.yuktisetu.institute.model.TenantStatus;

import java.time.Instant;

public record CollegeResponse(
        Long id,
        Long trustId,
        String name,
        String code,
        String logoUrl,
        String address,
        String primaryContactName,
        String primaryContactEmail,
        String primaryContactPhone,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}

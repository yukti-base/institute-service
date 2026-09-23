package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Partial update; null means "leave alone". See TrustPatchRequest for why this is separate from CollegeRequest. */
public record CollegePatchRequest(
        @Size(max = 255) String name,
        @Size(max = 32) String code,
        String logoUrl,
        String address,
        @Size(max = 255) String primaryContactName,
        @Email String primaryContactEmail,
        String primaryContactPhone
) {}

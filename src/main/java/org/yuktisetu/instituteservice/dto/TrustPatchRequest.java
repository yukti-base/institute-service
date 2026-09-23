package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Partial update. Every field is nullable and null means "leave alone".
 *
 * Distinct from TrustRequest, which PUT uses as a full replacement: sending a
 * PUT with logoUrl omitted blanks the logo, because the record is replaced by
 * what arrived. That is correct for PUT and wrong for the portal's inline edit,
 * where an admin fixing a typo in the contact phone has no reason to resend the
 * logo and would silently erase it. Hence a separate shape rather than reusing
 * the request DTO with looser validation.
 *
 * Blanking a nullable field on purpose is done by sending an empty string,
 * which is trimmed to null -- unambiguous, because null already means "absent".
 */
public record TrustPatchRequest(
        @Size(max = 255) String name,
        @Size(max = 32) String code,
        String logoUrl,
        @Email String primaryContactEmail,
        String primaryContactPhone
) {}

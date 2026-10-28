package org.yuktisetu.instituteservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.instituteservice.dto.DegreePatchRequest;
import org.yuktisetu.instituteservice.dto.DegreeRequest;
import org.yuktisetu.instituteservice.dto.DegreeResponse;
import org.yuktisetu.instituteservice.dto.StatusChangeRequest;
import org.yuktisetu.instituteservice.service.DegreeService;

import java.util.List;

/** The trust-wide degree catalogue. Anyone signed in can read it; only trust-level admins change it. */
@RestController
@RequestMapping("/degrees")
@RequiredArgsConstructor
public class DegreeController {

    private final DegreeService degreeService;

    @GetMapping
    public ResponseEntity<YuktiSetuResponse<List<DegreeResponse>>> list() {
        return ResponseEntity.ok(YuktiSetuResponse.success(degreeService.list()));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DegreeResponse>> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid DegreeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(degreeService.create(actor, request), 201, "Degree created"));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DegreeResponse>> patch(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid DegreePatchRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(degreeService.patch(actor, id, request), "Degree updated"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DegreeResponse>> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid StatusChangeRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                degreeService.setStatus(actor, id, request.status()), "Degree status updated"));
    }
}

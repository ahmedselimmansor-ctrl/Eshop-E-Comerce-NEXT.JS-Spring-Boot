package com.eshop.user.web;

import com.eshop.security.UserPrincipal;
import com.eshop.user.service.UserService;
import com.eshop.user.web.dto.ChangePasswordRequest;
import com.eshop.user.web.dto.UpdateProfileRequest;
import com.eshop.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own account.
 *
 * <p>Every handler derives the user id from the authenticated principal. No endpoint here accepts a
 * user id as a parameter - that is the difference between "edit my profile" and "edit anyone's
 * profile if you can guess a UUID".
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Account")
public class MeController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Current profile")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal caller) {
        return userService.profileOf(caller.id());
    }

    @PatchMapping
    @Operation(summary = "Update profile",
            description = "Partial update - omitted fields are left unchanged. "
                    + "Email is not editable here; changing it runs through its own verification flow.")
    public UserResponse update(@AuthenticationPrincipal UserPrincipal caller,
                               @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfileResponse(caller.id(), request);
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password",
            description = "Requires the current password even though the caller is authenticated, "
                    + "and revokes every other session on success.")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserPrincipal caller,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(caller.id(), request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Delete account",
            description = "Anonymises personal data and ends all sessions. Order and payment history "
                    + "is retained for accounting and dispute handling.")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal UserPrincipal caller) {
        userService.deleteOwnAccount(caller.id());
        return ResponseEntity.noContent().build();
    }
}

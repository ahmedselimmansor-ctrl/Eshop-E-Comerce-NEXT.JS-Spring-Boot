package com.eshop.user.web;

import com.eshop.security.UserPrincipal;
import com.eshop.user.service.AddressService;
import com.eshop.user.web.dto.AddressRequest;
import com.eshop.user.web.dto.AddressResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/addresses")
@RequiredArgsConstructor
@Tag(name = "Addresses")
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    @Operation(summary = "List saved addresses", description = "Default shipping address first.")
    public List<AddressResponse> list(@AuthenticationPrincipal UserPrincipal caller) {
        return AddressResponse.from(addressService.listForUser(caller.id()));
    }

    @GetMapping("/{addressId}")
    @Operation(summary = "Get one address")
    public AddressResponse get(@AuthenticationPrincipal UserPrincipal caller,
                               @PathVariable UUID addressId) {
        return AddressResponse.from(addressService.require(addressId, caller.id()));
    }

    @PostMapping
    @Operation(summary = "Add an address",
            description = "The first saved address automatically becomes the default for shipping and billing.")
    public ResponseEntity<AddressResponse> create(@AuthenticationPrincipal UserPrincipal caller,
                                                  @Valid @RequestBody AddressRequest request) {
        AddressResponse created = AddressResponse.from(addressService.create(caller.id(), request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{addressId}")
    @Operation(summary = "Update an address",
            description = "Past orders keep the address they were placed with and are unaffected.")
    public AddressResponse update(@AuthenticationPrincipal UserPrincipal caller,
                                  @PathVariable UUID addressId,
                                  @Valid @RequestBody AddressRequest request) {
        return AddressResponse.from(addressService.update(addressId, caller.id(), request));
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "Remove an address")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal caller,
                                       @PathVariable UUID addressId) {
        addressService.delete(addressId, caller.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{addressId}/default")
    @Operation(summary = "Make this the default address")
    public AddressResponse makeDefault(@AuthenticationPrincipal UserPrincipal caller,
                                       @PathVariable UUID addressId,
                                       @Parameter(description = "shipping or billing")
                                       @RequestParam(defaultValue = "shipping") String type) {
        return AddressResponse.from(addressService.makeDefault(addressId, caller.id(), type));
    }
}

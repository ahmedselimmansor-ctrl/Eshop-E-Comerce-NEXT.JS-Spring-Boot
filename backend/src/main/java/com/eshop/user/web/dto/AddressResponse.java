package com.eshop.user.web.dto;

import com.eshop.user.domain.Address;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(name = "AddressResponse")
public record AddressResponse(
        UUID id,
        String label,
        String recipientName,
        String phone,
        String countryCode,
        String governorate,
        String city,
        String area,
        String street,
        String building,
        String apartment,
        String floor,
        String landmark,
        String postalCode,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean defaultShipping,
        boolean defaultBilling,

        @Schema(description = "Single-line rendering for courier labels and confirmation emails")
        String formatted
) {

    public static AddressResponse from(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getLabel(),
                address.getRecipientName(),
                address.getPhone(),
                address.getCountryCode(),
                address.getGovernorate(),
                address.getCity(),
                address.getArea(),
                address.getStreet(),
                address.getBuilding(),
                address.getApartment(),
                address.getFloor(),
                address.getLandmark(),
                address.getPostalCode(),
                address.getLatitude(),
                address.getLongitude(),
                address.isDefaultShipping(),
                address.isDefaultBilling(),
                address.formatted());
    }

    public static List<AddressResponse> from(List<Address> addresses) {
        return addresses.stream().map(AddressResponse::from).toList();
    }
}

package com.eshop.user.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.User;
import com.eshop.user.repository.AddressRepository;
import com.eshop.user.repository.UserRepository;
import com.eshop.user.web.dto.AddressRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The customer's address book.
 *
 * <p>Every read and write is scoped by owner inside the repository query, so a request for someone
 * else's address id is indistinguishable from a request for one that does not exist. That is the
 * difference between a 404 and a working IDOR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    private static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addresses;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public List<Address> listForUser(UUID userId) {
        return addresses.findAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public Address require(UUID addressId, UUID userId) {
        return addresses.findByIdForUser(addressId, userId)
                .orElseThrow(() -> NotFoundException.of("Address", addressId));
    }

    @Transactional
    public Address create(UUID userId, AddressRequest request) {
        if (addresses.countByUserIdAndDeletedAtIsNull(userId) >= MAX_ADDRESSES_PER_USER) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Address book limit of " + MAX_ADDRESSES_PER_USER + " reached")
                    .withViolation("addresses", "LIMIT_REACHED",
                            "وصلت للحد الأقصى لعدد العناوين المحفوظة", MAX_ADDRESSES_PER_USER);
        }

        User user = users.findActiveById(userId)
                .orElseThrow(() -> NotFoundException.of("User", userId));

        Address address = new Address();
        address.setUser(user);
        apply(address, request);

        // The first address a customer saves becomes the default for both purposes, so checkout
        // has something preselected without an extra step.
        boolean isFirst = addresses.countByUserIdAndDeletedAtIsNull(userId) == 0;
        setDefaults(userId, address,
                Boolean.TRUE.equals(request.defaultShipping()) || isFirst,
                Boolean.TRUE.equals(request.defaultBilling()) || isFirst);

        Address saved = addresses.save(address);
        log.info("address_created user={} address={}", userId, saved.getId());
        return saved;
    }

    @Transactional
    public Address update(UUID addressId, UUID userId, AddressRequest request) {
        Address address = require(addressId, userId);
        apply(address, request);

        setDefaults(userId, address,
                Boolean.TRUE.equals(request.defaultShipping()),
                Boolean.TRUE.equals(request.defaultBilling()));

        return addresses.save(address);
    }

    /**
     * Soft delete: past orders hold their own frozen copy of the address, but keeping the row means
     * an accidental deletion can be reversed and any in-flight order still resolves its reference.
     */
    @Transactional
    public void delete(UUID addressId, UUID userId) {
        Address address = require(addressId, userId);
        address.setDeletedAt(Instant.now());
        address.setDefaultShipping(false);
        address.setDefaultBilling(false);
        addresses.save(address);

        // Promote another address so the customer is not left with no default at checkout.
        addresses.findAllForUser(userId).stream().findFirst().ifPresent(next -> {
            if (!next.isDefaultShipping()) {
                addresses.clearDefaultShipping(userId);
                next.setDefaultShipping(true);
                addresses.save(next);
            }
        });

        log.info("address_deleted user={} address={}", userId, addressId);
    }

    @Transactional
    public Address makeDefault(UUID addressId, UUID userId, String type) {
        Address address = require(addressId, userId);
        boolean shipping = !"billing".equalsIgnoreCase(type);
        setDefaults(userId, address, shipping, !shipping);
        return addresses.save(address);
    }

    /**
     * Applies default flags while honouring the partial unique indexes.
     *
     * <p>The previous default must be cleared and flushed before the new one is set; doing it in the
     * other order trips {@code uq_addresses_default_shipping} mid-transaction.
     */
    private void setDefaults(UUID userId, Address address, boolean shipping, boolean billing) {
        if (shipping) {
            addresses.clearDefaultShipping(userId);
            address.setDefaultShipping(true);
        }
        if (billing) {
            addresses.clearDefaultBilling(userId);
            address.setDefaultBilling(true);
        }
    }

    private void apply(Address address, AddressRequest request) {
        address.setLabel(trimToNull(request.label()));
        address.setRecipientName(request.recipientName().trim());
        address.setPhone(request.phone().trim());
        address.setCountryCode(request.countryCodeOrDefault());
        address.setGovernorate(request.governorate().trim());
        address.setCity(request.city().trim());
        address.setArea(trimToNull(request.area()));
        address.setStreet(request.street().trim());
        address.setBuilding(trimToNull(request.building()));
        address.setApartment(trimToNull(request.apartment()));
        address.setFloor(trimToNull(request.floor()));
        address.setLandmark(trimToNull(request.landmark()));
        address.setPostalCode(trimToNull(request.postalCode()));
        address.setLatitude(request.latitude());
        address.setLongitude(request.longitude());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

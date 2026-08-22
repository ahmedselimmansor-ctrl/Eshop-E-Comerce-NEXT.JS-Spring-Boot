package com.eshop.catalog.service;

import com.eshop.catalog.domain.Attribute;
import com.eshop.catalog.domain.AttributeValue;
import com.eshop.catalog.repository.AttributeRepository;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttributeService {

    private final AttributeRepository attributes;

    @Cacheable(value = RedisConfig.CACHE_ATTRIBUTES, key = "'filterable'")
    @Transactional(readOnly = true)
    public List<Attribute> filterable() {
        return attributes.findFilterable();
    }

    @Cacheable(value = RedisConfig.CACHE_ATTRIBUTES, key = "'all'")
    @Transactional(readOnly = true)
    public List<Attribute> all() {
        return attributes.findAllWithValues();
    }

    @Transactional(readOnly = true)
    public Attribute require(UUID id) {
        return attributes.findWithValuesById(id)
                .orElseThrow(() -> NotFoundException.of("Attribute", id));
    }

    @Transactional(readOnly = true)
    public Attribute requireByCode(String code) {
        return attributes.findByCode(code)
                .orElseThrow(() -> NotFoundException.of("Attribute", code));
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_ATTRIBUTES, allEntries = true)
    public Attribute create(Attribute attribute) {
        if (attributes.existsByCode(attribute.getCode())) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "Attribute code already exists")
                    .withViolation("code", "DUPLICATE", "هذا الكود مستخدم بالفعل", attribute.getCode());
        }
        return attributes.save(attribute);
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_ATTRIBUTES, allEntries = true)
    public Attribute update(UUID id, Attribute changes) {
        Attribute attribute = require(id);

        attribute.setNameAr(changes.getNameAr());
        attribute.setNameEn(changes.getNameEn());
        attribute.setFilterable(changes.isFilterable());
        attribute.setSearchable(changes.isSearchable());
        attribute.setRequired(changes.isRequired());
        attribute.setUnit(changes.getUnit());
        attribute.setPosition(changes.getPosition());

        // Type and variant-axis status are immutable once set. Flipping either would silently
        // invalidate every product and variant already bound to this attribute - a colour that
        // stops being an axis leaves orphaned variants with no way to select them.
        if (changes.getType() != attribute.getType()
                || changes.isVariantAxis() != attribute.isVariantAxis()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Attribute type and variant-axis flag cannot be changed after creation")
                    .withViolation("type", "IMMUTABLE",
                            "لا يمكن تغيير نوع الخاصية بعد إنشائها", changes.getType());
        }

        return attributes.save(attribute);
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_ATTRIBUTES, allEntries = true)
    public AttributeValue addValue(UUID attributeId, AttributeValue value) {
        Attribute attribute = require(attributeId);

        if (!attribute.hasPredefinedValues()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Only SELECT, MULTISELECT and COLOR attributes have predefined values")
                    .withViolation("type", "NOT_ENUMERATED",
                            "هذا النوع من الخصائص لا يقبل قيماً محددة مسبقاً", attribute.getType());
        }
        boolean duplicate = attribute.getValues().stream()
                .anyMatch(existing -> existing.getCode().equals(value.getCode()));
        if (duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "Value code already exists")
                    .withViolation("code", "DUPLICATE", "هذه القيمة موجودة بالفعل", value.getCode());
        }

        value.setAttribute(attribute);
        attribute.getValues().add(value);
        attributes.save(attribute);
        return value;
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_ATTRIBUTES, allEntries = true)
    public void removeValue(UUID attributeId, UUID valueId) {
        Attribute attribute = require(attributeId);
        // orphanRemoval on Attribute.values turns this into a DELETE; the foreign keys from
        // variant_attribute_values are RESTRICT, so a value still in use rejects the delete rather
        // than silently unmaking a variant.
        attribute.getValues().removeIf(value -> value.getId().equals(valueId));
        attributes.save(attribute);
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_ATTRIBUTES, allEntries = true)
    public void delete(UUID id) {
        attributes.delete(require(id));
        log.info("attribute_deleted id={}", id);
    }
}

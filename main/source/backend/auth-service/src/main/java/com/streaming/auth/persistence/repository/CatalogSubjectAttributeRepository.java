package com.streaming.auth.persistence.repository;

import com.streaming.auth.persistence.entity.CatalogSubjectAttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read-only catalog of allowed JWT {@code attr} keys and their JSON value kinds.
 */
public interface CatalogSubjectAttributeRepository
        extends JpaRepository<CatalogSubjectAttributeEntity, String> {
}

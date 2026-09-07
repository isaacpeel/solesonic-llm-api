package com.solesonic.repository;

import com.solesonic.model.address.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    /**
     * Ownership lives on {@code UserPreferences.addressId}, not on {@code Address} itself, so the
     * check is a join in the query rather than a fetch-then-compare in the service.
     */
    @Query("""
            select address from Address address
            join UserPreferences userPreferences on userPreferences.addressId = address.id
            where address.id = :addressId and userPreferences.userId = :userId
            """)
    Optional<Address> findByIdAndUserId(UUID addressId, UUID userId);
}

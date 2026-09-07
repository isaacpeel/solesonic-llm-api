package com.solesonic.api.address;

import com.solesonic.model.address.Address;
import com.solesonic.service.address.AddressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * A generic postal address, owned by nothing until it is linked to a {@code UserPreferences} row
 * via {@code PUT /users/{userId}/preferences/{addressId}}. Ownership after that point is a join
 * against {@code UserPreferences.addressId} inside {@link AddressService}, not a stored column
 * here, so the caller is taken from {@link com.solesonic.scope.UserRequestContext} rather than a
 * path segment.
 */
@RestController
@RequestMapping("/addresses")
public class AddressController {
    private static final Logger log = LoggerFactory.getLogger(AddressController.class);

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @PostMapping
    public ResponseEntity<Address> create(@RequestBody Address address) {
        log.info("Creating address");

        Address created = addressService.create(address);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/addresses/{addressId}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{addressId}")
    public ResponseEntity<Address> get(@PathVariable UUID addressId) {
        log.debug("Getting address {}", addressId);

        return ResponseEntity.ok(addressService.get(addressId));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<Address> update(@PathVariable UUID addressId, @RequestBody Address address) {
        log.info("Updating address {}", addressId);

        return ResponseEntity.ok(addressService.update(addressId, address));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(@PathVariable UUID addressId) {
        log.info("Deleting address {}", addressId);

        addressService.delete(addressId);

        return ResponseEntity.noContent().build();
    }
}

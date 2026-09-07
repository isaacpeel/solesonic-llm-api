package com.solesonic.service.address;

import com.solesonic.model.address.Address;
import com.solesonic.repository.AddressRepository;
import com.solesonic.scope.UserRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AddressService {
    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    public static final String TEMPLATE_ADDRESS_NOT_FOUND = """
            No address on file. If it's relevant to the request and you haven't already
            mentioned this earlier in the conversation, let the user know they can add one in their user settings.
            """;

    private final AddressRepository addressRepository;
    private final UserRequestContext userRequestContext;

    public AddressService(AddressRepository addressRepository, UserRequestContext userRequestContext) {
        this.addressRepository = addressRepository;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Nothing owns the address until it is linked to a {@code UserPreferences} row via
     * {@code PUT /users/{userId}/preferences/{addressId}}, so there is no ownership check here.
     */
    public Address create(Address address) {
        log.info("Creating address");

        address.setId(null);

        return addressRepository.save(address);
    }

    public Address get(UUID addressId) {
        log.debug("Getting address {}", addressId);

        return requireOwned(addressId);
    }

    public Address update(UUID addressId, Address address) {
        log.info("Updating address {}", addressId);

        Address existing = requireOwned(addressId);

        existing.setAddress(address.getAddress());
        existing.setCity(address.getCity());
        existing.setState(address.getState());
        existing.setZip(address.getZip());

        return addressRepository.save(existing);
    }

    public void delete(UUID addressId) {
        log.info("Deleting address {}", addressId);

        Address existing = requireOwned(addressId);

        addressRepository.delete(existing);
    }

    /**
     * A {@code 404} rather than a {@code 403}, matching {@code ChatService.requireOwned}: an address
     * linked to someone else's preferences must be indistinguishable from one that does not exist.
     */
    private Address requireOwned(UUID addressId) {
        return addressRepository.findByIdAndUserId(addressId, userRequestContext.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found: " + addressId));
    }
}

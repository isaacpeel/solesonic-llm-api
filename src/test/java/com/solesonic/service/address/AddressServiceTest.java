package com.solesonic.service.address;

import com.solesonic.model.address.Address;
import com.solesonic.repository.AddressRepository;
import com.solesonic.scope.UserRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private UserRequestContext userRequestContext;

    @InjectMocks
    private AddressService addressService;

    private UUID userId;
    private UUID addressId;
    private Address address;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        addressId = UUID.randomUUID();

        address = new Address();
        address.setId(addressId);
        address.setAddress("123 Main St");
        address.setCity("Springfield");
        address.setState("IL");
        address.setZip("62701");

        lenient().when(userRequestContext.getUserId()).thenReturn(userId);
    }

    @Test
    void createSavesANewAddressWithNoOwnershipCheck() {
        Address incoming = new Address();
        incoming.setId(UUID.randomUUID());
        incoming.setAddress("123 Main St");

        when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Address result = addressService.create(incoming);

        assertThat(result.getAddress()).isEqualTo("123 Main St");

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();

        verify(userRequestContext, never()).getUserId();
    }

    @Test
    void getReturnsTheAddressWhenLinkedToTheCaller() {
        when(addressRepository.findByIdAndUserId(addressId, userId)).thenReturn(Optional.of(address));

        Address result = addressService.get(addressId);

        assertThat(result).isEqualTo(address);
    }

    @Test
    void getAnAddressNotLinkedToTheCallerIsNotFound() {
        when(addressRepository.findByIdAndUserId(addressId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.get(addressId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateChangesTheFourFields() {
        when(addressRepository.findByIdAndUserId(addressId, userId)).thenReturn(Optional.of(address));
        when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Address changes = new Address();
        changes.setAddress("456 Oak Ave");
        changes.setCity("Shelbyville");
        changes.setState("IL");
        changes.setZip("62565");

        Address result = addressService.update(addressId, changes);

        assertThat(result.getAddress()).isEqualTo("456 Oak Ave");
        assertThat(result.getCity()).isEqualTo("Shelbyville");
        assertThat(result.getState()).isEqualTo("IL");
        assertThat(result.getZip()).isEqualTo("62565");
    }

    @Test
    void updatingAnAddressNotLinkedToTheCallerIsNotFound() {
        when(addressRepository.findByIdAndUserId(addressId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.update(addressId, new Address()))
                .isInstanceOf(ResponseStatusException.class);

        verify(addressRepository, never()).save(any(Address.class));
    }

    @Test
    void deleteRemovesTheAddress() {
        when(addressRepository.findByIdAndUserId(addressId, userId)).thenReturn(Optional.of(address));

        addressService.delete(addressId);

        verify(addressRepository).delete(address);
    }

    @Test
    void deletingAnAddressNotLinkedToTheCallerIsNotFound() {
        when(addressRepository.findByIdAndUserId(addressId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.delete(addressId))
                .isInstanceOf(ResponseStatusException.class);

        verify(addressRepository, never()).delete(any(Address.class));
    }
}

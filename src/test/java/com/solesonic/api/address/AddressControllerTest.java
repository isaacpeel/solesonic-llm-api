package com.solesonic.api.address;

import com.solesonic.model.address.Address;
import com.solesonic.service.address.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AddressControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AddressService addressService;

    @InjectMocks
    private AddressController addressController;

    private UUID addressId;
    private Address address;

    @BeforeEach
    void setUp() {
        addressId = UUID.randomUUID();

        address = new Address();
        address.setId(addressId);
        address.setAddress("123 Main St");
        address.setCity("Springfield");
        address.setState("IL");
        address.setZip("62701");

        mockMvc = MockMvcBuilders.standaloneSetup(addressController).build();
    }

    @Test
    void createReturnsCreatedWithLocation() throws Exception {
        when(addressService.create(any(Address.class))).thenReturn(address);

        mockMvc.perform(post("/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"123 Main St\",\"city\":\"Springfield\",\"state\":\"IL\",\"zip\":\"62701\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/addresses/" + addressId))
                .andExpect(jsonPath("$.id").value(addressId.toString()));
    }

    @Test
    void getReturnsTheAddress() throws Exception {
        when(addressService.get(addressId)).thenReturn(address);

        mockMvc.perform(get("/addresses/{addressId}", addressId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Springfield"));
    }

    @Test
    void getAnUnlinkedAddressIsNotFound() throws Exception {
        when(addressService.get(addressId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

        mockMvc.perform(get("/addresses/{addressId}", addressId))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateReturnsTheUpdatedAddress() throws Exception {
        when(addressService.update(eq(addressId), any(Address.class))).thenReturn(address);

        mockMvc.perform(put("/addresses/{addressId}", addressId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"123 Main St\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Springfield"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/addresses/{addressId}", addressId))
                .andExpect(status().isNoContent());

        verify(addressService).delete(addressId);
    }
}

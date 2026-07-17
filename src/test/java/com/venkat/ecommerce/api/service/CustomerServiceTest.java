package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.CustomerRequest;
import com.venkat.ecommerce.api.dto.CustomerResponse;
import com.venkat.ecommerce.api.entity.Customer;
import com.venkat.ecommerce.api.exception.DuplicateResourceException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    private CustomerRequest sampleRequest() {
        return CustomerRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john.doe@example.com")
                .phone("+1-202-555-0101")
                .address("123 Maple Street")
                .build();
    }

    @Test
    void should_registerCustomer_when_emailIsUnique() {
        // Arrange
        CustomerRequest request = sampleRequest();
        when(customerRepository.findByEmail("john.doe@example.com")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // Act
        CustomerResponse response = customerService.create(request);

        // Assert
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("john.doe@example.com");
    }

    @Test
    void should_throwDuplicateResource_when_emailAlreadyExists() {
        // Arrange
        CustomerRequest request = sampleRequest();
        when(customerRepository.findByEmail("john.doe@example.com"))
                .thenReturn(Optional.of(Customer.builder().id(2L).email("john.doe@example.com").build()));

        // Act & Assert
        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("email");
        verify(customerRepository, never()).save(any());
    }

    @Test
    void should_throwResourceNotFound_when_customerDoesNotExist() {
        // Arrange
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> customerService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
    }
}

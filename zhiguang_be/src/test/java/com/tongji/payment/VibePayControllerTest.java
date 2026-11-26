package com.tongji.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayAipayNowpaydirectPurchaseConsultRequest;
import com.alipay.api.response.AlipayAipayNowpaydirectPurchaseConsultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.tokenservice.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VibePayControllerTest {
    @Test
    void bindsBuyerToAuthenticatedUserAndOnlyExposesPurchaseLinkWhenRequired() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_type")).thenReturn("access");
        when(jwtService.extractUserId(jwt)).thenReturn(42L);
        VibePayService payment = spy(new VibePayService(new ObjectMapper()));
        VibePayController controller = new VibePayController(payment, jwtService);
        ReflectionTestUtils.setField(payment, "productId", "product");
        ReflectionTestUtils.setField(payment, "callbackUrl", "");
        AlipayClient client = mock(AlipayClient.class);
        doReturn(client).when(payment).client();
        AlipayAipayNowpaydirectPurchaseConsultResponse response = mock(AlipayAipayNowpaydirectPurchaseConsultResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getBody()).thenReturn("{\"alipay_aipay_nowpaydirect_purchase_consult_response\":{\"code\":\"10000\",\"decision\":\"ALLOW\",\"purchase_url\":\"alipays://unexpected\"}}");
        when(client.execute(any(AlipayAipayNowpaydirectPurchaseConsultRequest.class))).thenReturn(response);
        var result = controller.consult(jwt);
        assertEquals("ALLOW", result.path("decision").asText());
        assertFalse(result.has("purchase_url"));
        var request = org.mockito.ArgumentCaptor.forClass(AlipayAipayNowpaydirectPurchaseConsultRequest.class);
        verify(client).execute(request.capture());
        var biz = new ObjectMapper().readTree(request.getValue().getBizContent());
        assertEquals("42", biz.path("external_buyer_id").asText());
        assertEquals("product", biz.path("out_product_id").asText());
    }


    @Test
    void purchaseEntryIsReturnedOnlyForEnabledProducts() throws Exception {
        JwtService service = mock(JwtService.class);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_type")).thenReturn("access");
        when(service.extractUserId(jwt)).thenReturn(17L);
        VibePayService payment = spy(new VibePayService(new ObjectMapper()));
        VibePayController controller = new VibePayController(payment, service);
        ReflectionTestUtils.setField(payment, "productId", "product");
        ReflectionTestUtils.setField(payment, "callbackUrl", "");
        AlipayClient client = mock(AlipayClient.class);
        doReturn(client).when(payment).client();
        AlipayAipayNowpaydirectPurchaseConsultResponse response = mock(AlipayAipayNowpaydirectPurchaseConsultResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(client.execute(any(AlipayAipayNowpaydirectPurchaseConsultRequest.class))).thenReturn(response);
        when(response.getBody()).thenReturn("{\"alipay_aipay_nowpaydirect_purchase_consult_response\":{\"code\":\"10000\",\"decision\":\"PURCHASE_REQUIRED\",\"capability_status\":\"ENABLED\",\"purchase_url\":\"alipays://pay\"}}");
        assertEquals("alipays://pay", controller.consult(jwt).path("purchase_url").asText());
        when(response.getBody()).thenReturn("{\"alipay_aipay_nowpaydirect_purchase_consult_response\":{\"code\":\"10000\",\"decision\":\"PURCHASE_REQUIRED\",\"capability_status\":\"DISABLED\",\"purchase_url\":\"alipays://pay\"}}");
        assertFalse(controller.consult(jwt).has("purchase_url"));
        when(response.getBody()).thenReturn("{\"alipay_aipay_nowpaydirect_purchase_consult_response\":{\"code\":\"10000\",\"decision\":\"DENY\",\"purchase_url\":\"alipays://pay\"}}");
        assertFalse(controller.consult(jwt).has("purchase_url"));
    }

    @Test
    void gatewayFailureDoesNotBecomeAValidPurchase() throws Exception {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_type")).thenReturn("access");
        JwtService service = mock(JwtService.class);
        when(service.extractUserId(jwt)).thenReturn(17L);
        VibePayService payment = spy(new VibePayService(new ObjectMapper()));
        VibePayController controller = new VibePayController(payment, service);
        ReflectionTestUtils.setField(payment, "productId", "product");
        ReflectionTestUtils.setField(payment, "callbackUrl", "");
        AlipayClient client = mock(AlipayClient.class);
        doReturn(client).when(payment).client();
        AlipayAipayNowpaydirectPurchaseConsultResponse response = mock(AlipayAipayNowpaydirectPurchaseConsultResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(client.execute(any(AlipayAipayNowpaydirectPurchaseConsultRequest.class))).thenReturn(response);
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> controller.consult(jwt)).getStatusCode().value());
    }

    @Test
    void refusesRefreshTokensBeforeCallingAlipay() {
        VibePayService payment = mock(VibePayService.class);
        VibePayController controller = new VibePayController(payment, mock(JwtService.class));
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_type")).thenReturn("refresh");
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.consult(jwt)).getStatusCode().value());
        verifyNoInteractions(payment);
    }
}

package com.tongji.payment;

import com.alipay.api.*;
import com.alipay.api.request.AlipayAipayNowpaydirectChargeQueryRequest;
import com.alipay.api.request.AlipayAipayNowpaydirectPurchaseConsultRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.auth.tokenservice.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/payment")
public class VibePayController {
    private final ObjectMapper mapper;
    private final JwtService jwtService;
    @Value("${ALIPAY_APP_ID:}") private String appId;
    @Value("${ALIPAY_PRIVATE_KEY:}") private String privateKey;
    @Value("${ALIPAY_PUBLIC_KEY:}") private String publicKey;
    @Value("${VIBE_PAY_PRODUCT_ID:DIRECT_235811fb77c636e2a1a45d5d1ff0594f}") private String productId;
    @Value("${VIBE_PAY_CALLBACK_URL:}") private String callbackUrl;

    public VibePayController(ObjectMapper mapper, JwtService jwtService) {
        this.mapper = mapper;
        this.jwtService = jwtService;
    }

    @GetMapping("/product")
    public JsonNode product(@AuthenticationPrincipal Jwt jwt) {
        requireAccess(jwt);
        try {
            AlipayAipayNowpaydirectChargeQueryRequest request = new AlipayAipayNowpaydirectChargeQueryRequest();
            request.setBizContent(mapper.createObjectNode().put("out_product_id", productId).toString());
            JsonNode result = checked(client().execute(request), "alipay_aipay_nowpaydirect_charge_query_response");
            ObjectNode safe = mapper.createObjectNode();
            for (String key : new String[]{"product_name", "capability_status", "billing_mode", "charging_options"}) {
                if (result.has(key)) safe.set(key, result.get(key));
            }
            return safe;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝商品查询失败", e); }
    }

    @GetMapping("/consult")
    public JsonNode consult(@AuthenticationPrincipal Jwt jwt) {
        requireAccess(jwt);
        try {
            ObjectNode biz = mapper.createObjectNode().put("out_product_id", productId)
                    .put("external_buyer_id", String.valueOf(jwtService.extractUserId(jwt)));
            if (!callbackUrl.isBlank()) {
                if (!callbackUrl.startsWith("https://")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付回跳地址必须为 HTTPS");
                biz.put("callback_url", callbackUrl);
            }
            AlipayAipayNowpaydirectPurchaseConsultRequest request = new AlipayAipayNowpaydirectPurchaseConsultRequest();
            request.setBizContent(biz.toString());
            JsonNode result = checked(client().execute(request), "alipay_aipay_nowpaydirect_purchase_consult_response");
            String decision = result.path("decision").asText();
            if (!java.util.Set.of("ALLOW", "PURCHASE_REQUIRED", "DENY").contains(decision))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝返回未知权益状态");
            ObjectNode safe = mapper.createObjectNode();
            for (String key : new String[]{"decision", "reason_code", "product_name", "capability_status", "charging_options"}) {
                if (result.has(key)) safe.set(key, result.get(key));
            }
            if ("PURCHASE_REQUIRED".equals(decision) && "ENABLED".equals(result.path("capability_status").asText())) {
                for (String key : new String[]{"purchase_url", "purchase_qr_code"}) if (result.has(key)) safe.set(key, result.get(key));
            }
            return safe;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝权益查询失败", e); }
    }

    private void requireAccess(Jwt jwt) {
        if (jwt == null || !"access".equals(jwt.getClaimAsString("token_type")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }

    AlipayClient client() throws Exception {
        if (appId.isBlank() || privateKey.isBlank() || publicKey.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付尚未配置，请联系管理员");
        AlipayConfig config = new AlipayConfig();
        config.setServerUrl("https://openapi.alipay.com/gateway.do");
        config.setAppId(appId);
        config.setPrivateKey(privateKey);
        config.setAlipayPublicKey(publicKey);
        config.setFormat("json");
        config.setCharset("UTF-8");
        config.setSignType("RSA2");
        return new DefaultAlipayClient(config);
    }

    private JsonNode checked(AlipayResponse response, String key) throws Exception {
        if (!response.isSuccess())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝接口调用失败: " + response.getCode() + " / " + response.getSubCode());
        JsonNode result = mapper.readTree(response.getBody()).path(key);
        if (result.isMissingNode() || !"10000".equals(result.path("code").asText()))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝返回数据异常");
        return result;
    }
}

package com.tongji.auth.api.dto;

import com.tongji.auth.model.IdentifierType;
import com.tongji.auth.verification.VerificationScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendCodeRequest(
        @NotNull(message = "场景不能为空") VerificationScene scene,
        @NotNull(message = "账号类型不能为空") IdentifierType identifierType,
        @NotBlank(message = "账号不能为空") String identifier,
        String deliveryEmail
) {
}

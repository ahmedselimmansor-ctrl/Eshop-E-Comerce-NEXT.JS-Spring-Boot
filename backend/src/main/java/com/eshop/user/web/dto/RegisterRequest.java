package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "RegisterRequest")
public record RegisterRequest(

        @NotBlank
        @Size(min = 2, max = 80)
        String firstName,

        @Size(max = 80)
        String lastName,

        @NotBlank
        @Email
        @Size(max = 191)
        String email,

        /*
         * Egyptian mobile numbers, local or +20 form. Optional at registration — asking for a phone
         * up front measurably depresses sign-up, and checkout collects one anyway.
         */
        @Pattern(regexp = "^(\\+20|0)?1[0125][0-9]{8}$", message = "رقم الهاتف غير صحيح")
        String phone,

        /*
         * Only a length floor is enforced. Composition rules (an uppercase, a digit, a symbol) push
         * people toward predictable substitutions such as "Password1!" while blocking genuinely
         * strong passphrases; NIST SP 800-63B recommends length plus a breach check instead, which
         * AuthService applies separately.
         */
        @NotBlank
        @Size(min = 8, max = 128, message = "كلمة المرور يجب أن تكون 8 أحرف على الأقل")
        String password,

        @Pattern(regexp = "^(ar|en)$")
        String locale,

        Boolean marketingOptIn
) {
    public String localeOrDefault() {
        return locale == null || locale.isBlank() ? "ar" : locale;
    }

    public boolean marketingOptInOrDefault() {
        return Boolean.TRUE.equals(marketingOptIn);
    }
}

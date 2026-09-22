package com.notepad.service;

import com.notepad.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邮箱验证码。
 * <p>
 * 这是个免登录接口，任何人不带凭据就能调用，所以除了校验逻辑本身，
 * 还要守住「存储不会被换着邮箱刷爆」这条上界。
 */
class VerificationCodeServiceTest {

    private static final String EMAIL = "alice@example.com";

    private void assertCode(BusinessException e, int expected) {
        assertThat(e.getCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("生成 6 位数字验证码，校验通过后可用")
    void generatesSixDigitCode() {
        VerificationCodeService service = new VerificationCodeService();

        String code = service.generate(EMAIL);

        assertThat(code).hasSize(6).containsOnlyDigits();
        assertThatCode(() -> service.verify(EMAIL, code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("验证码用过即失效，不能重放")
    void codeIsConsumedOnSuccess() {
        VerificationCodeService service = new VerificationCodeService();
        String code = service.generate(EMAIL);

        service.verify(EMAIL, code);

        assertThatThrownBy(() -> service.verify(EMAIL, code))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertCode((BusinessException) e, 400));
    }

    @Test
    @DisplayName("60 秒内不允许重复发送")
    void rejectsResendWithinInterval() {
        VerificationCodeService service = new VerificationCodeService();
        service.generate(EMAIL);

        assertThatThrownBy(() -> service.generate(EMAIL))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertCode((BusinessException) e, 400));
    }

    @Test
    @DisplayName("验证码错误时返回 400，不区分「错」和「过期」")
    void rejectsWrongCode() {
        VerificationCodeService service = new VerificationCodeService();
        service.generate(EMAIL);

        assertThatThrownBy(() -> service.verify(EMAIL, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("验证码错误或已过期");
    }

    @Test
    @DisplayName("连续错 5 次后作废验证码，逼用户重新获取，避免被暴力穷举")
    void invalidatesAfterMaxAttempts() {
        VerificationCodeService service = new VerificationCodeService();
        String code = service.generate(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.verify(EMAIL, wrong))
                    .hasMessageContaining("验证码错误");
        }
        // 第 5 次：提示换成「次数过多」，同时把这条记录删掉
        assertThatThrownBy(() -> service.verify(EMAIL, wrong))
                .hasMessageContaining("次数过多");

        // 作废之后连正确的码也进不去了
        assertThatThrownBy(() -> service.verify(EMAIL, code))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("没发过码就校验，返回 400 而不是 NPE")
    void rejectsVerifyWithoutGenerate() {
        VerificationCodeService service = new VerificationCodeService();

        assertThatThrownBy(() -> service.verify("nobody@example.com", "123456"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertCode((BusinessException) e, 400));
    }

    @Test
    @DisplayName("存储有硬上限：换着邮箱刷不能把堆撑爆（这条路径没有别的回收机制）")
    void capsStoredEntries() {
        VerificationCodeService service = new VerificationCodeService();

        // 上限 5000；条目只会被 verify 删除，而下面这些都不会被校验
        for (int i = 0; i < 5000; i++) {
            service.generate("user" + i + "@example.com");
        }

        assertThatThrownBy(() -> service.generate("one-too-many@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertCode((BusinessException) e, 429));
    }

    @Test
    @DisplayName("不同邮箱互不影响")
    void differentEmailsAreIndependent() {
        VerificationCodeService service = new VerificationCodeService();
        String first = service.generate("a@example.com");
        String second = service.generate("b@example.com");

        assertThatCode(() -> service.verify("a@example.com", first)).doesNotThrowAnyException();
        assertThatCode(() -> service.verify("b@example.com", second)).doesNotThrowAnyException();
    }
}

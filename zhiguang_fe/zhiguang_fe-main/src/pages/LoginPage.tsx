import { FormEvent, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import clsx from "clsx";
import { useAuth } from "@/context/AuthContext";
import type { LoginRequest } from "@/types/auth";
import { authService } from "@/services/authService";
import styles from "./LoginPage.module.css";

type LocationState = {
  from?: string;
};

const OTP_LENGTH = 6;

const LoginPage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { login, isLoading, user } = useAuth();
  const [phone, setPhone] = useState("");
  const [deliveryEmail, setDeliveryEmail] = useState("");
  const [digits, setDigits] = useState<string[]>(Array(OTP_LENGTH).fill(""));
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);

  const inputsRef = useRef<(HTMLInputElement | null)[]>([]);
  const from = (location.state as LocationState | undefined)?.from ?? "/";

  useEffect(() => {
    if (!isLoading && user) {
      navigate(from, { replace: true });
    }
  }, [isLoading, user, navigate, from]);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown(prev => prev - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  const updateDigitsAndCode = (newDigits: string[]) => {
    setDigits(newDigits);
    const combined = newDigits.join("");
    setCode(combined);
  };

  useEffect(() => {
    if (typeof window === "undefined" || !("OTPCredential" in window)) return;
    const ac = new AbortController();

    (navigator.credentials as unknown as { get: (options: unknown) => Promise<{ code?: string } | null> })
      .get({
        otp: { transport: ["sms"] },
        signal: ac.signal
      })
      .then(otp => {
        const otpCode = otp?.code;
        if (otpCode) {
          const clean = otpCode.replace(/\D/g, "").slice(0, OTP_LENGTH);
          const newDigits = Array(OTP_LENGTH).fill("");
          for (let i = 0; i < clean.length; i++) {
            newDigits[i] = clean[i];
          }
          updateDigitsAndCode(newDigits);
          inputsRef.current[Math.min(clean.length, OTP_LENGTH - 1)]?.focus();
        }
      })
      .catch(() => {});

    return () => {
      ac.abort();
    };
  }, [sendingCode]);

  const handlePaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pastedText = e.clipboardData.getData("text");
    const numericOnly = pastedText.replace(/\D/g, "").slice(0, OTP_LENGTH);
    if (!numericOnly) return;

    const newDigits = Array(OTP_LENGTH).fill("");
    for (let i = 0; i < OTP_LENGTH; i++) {
      newDigits[i] = numericOnly[i] || "";
    }
    updateDigitsAndCode(newDigits);

    const focusTarget = Math.min(numericOnly.length, OTP_LENGTH - 1);
    inputsRef.current[focusTarget]?.focus();
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace") {
      e.preventDefault();
      const newDigits = [...digits];
      if (newDigits[index]) {
        newDigits[index] = "";
        updateDigitsAndCode(newDigits);
      } else if (index > 0) {
        newDigits[index - 1] = "";
        updateDigitsAndCode(newDigits);
        inputsRef.current[index - 1]?.focus();
      }
    } else if (e.key === "ArrowLeft" && index > 0) {
      e.preventDefault();
      inputsRef.current[index - 1]?.focus();
    } else if (e.key === "ArrowRight" && index < OTP_LENGTH - 1) {
      e.preventDefault();
      inputsRef.current[index + 1]?.focus();
    } else if (e.key === "Enter" && !isDisabled) {
      e.preventDefault();
      const form = e.currentTarget.form;
      if (form) form.requestSubmit();
    }
  };

  const handleDigitChange = (index: number, e: React.ChangeEvent<HTMLInputElement>) => {
    const rawVal = e.target.value;
    const numeric = rawVal.replace(/\D/g, "");

    if (!numeric) {
      const newDigits = [...digits];
      newDigits[index] = "";
      updateDigitsAndCode(newDigits);
      return;
    }

    if (numeric.length > 1) {
      const newDigits = [...digits];
      for (let i = 0; i < OTP_LENGTH; i++) {
        const offset = i - index;
        if (offset >= 0 && offset < numeric.length) {
          newDigits[i] = numeric[offset];
        }
      }
      updateDigitsAndCode(newDigits);
      const nextIndex = Math.min(index + numeric.length - 1, OTP_LENGTH - 1);
      inputsRef.current[nextIndex]?.focus();
      return;
    }

    const singleDigit = numeric[numeric.length - 1];
    const newDigits = [...digits];
    newDigits[index] = singleDigit;
    updateDigitsAndCode(newDigits);

    if (index < OTP_LENGTH - 1) {
      inputsRef.current[index + 1]?.focus();
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (code.length < OTP_LENGTH) {
      setError(`请输入完整的 ${OTP_LENGTH} 位验证码`);
      return;
    }
    setError(null);
    setSubmitting(true);

    try {
      const payload: LoginRequest = {
        identifierType: "PHONE",
        identifier: phone,
        code
      };
      await login(payload);
      navigate(from, { replace: true });
    } catch (err) {
      const message = err instanceof Error ? err.message : "登录失败，请稍后重试";
      setError(message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleGitHubLogin = async () => {
    setError(null);
    try {
      const response = await authService.getGitHubLoginUrl();
      if (response.code === 10001 && response.loginUrl) {
        window.location.href = response.loginUrl;
      }
    } catch {
      setError("获取 GitHub 授权链接失败");
    }
  };

  const handleCampusLogin = async () => {
    setError(null);
    try {
      const response = await authService.getCampusLoginUrl();
      if (response.code === 10001 && response.loginUrl) {
        window.location.href = response.loginUrl;
      }
    } catch {
      setError("获取校园账号授权链接失败");
    }
  };

  const handleSendCode = async () => {
    if (!phone) {
      setError("请先填写手机号码");
      return;
    }
    setError(null);
    setSendingCode(true);
    try {
      const response = await authService.sendCode({
        scene: "LOGIN",
        identifierType: "PHONE",
        identifier: phone,
        deliveryEmail: deliveryEmail || undefined
      });
      setCountdown(Math.max(1, response.expireSeconds ?? 300));
      inputsRef.current[0]?.focus();
    } catch (err) {
      const info = err instanceof Error ? err.message : "验证码发送失败";
      setError(info);
    } finally {
      setSendingCode(false);
    }
  };

  const isDisabled = submitting || !phone || code.length < OTP_LENGTH;

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <button
          type="button"
          className={styles.homeButton}
          onClick={() => navigate("/")}
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            <polyline points="9 22 9 12 15 12 15 22" />
          </svg>
          前往首页
        </button>

        <div className={styles.titleBlock}>
          <h1 className={styles.title}>欢迎</h1>
          <p className={styles.subtitle}>登录知光，与知识发光</p>
        </div>

        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          <div className={clsx(styles.floatingField, Boolean(phone) && styles.hasValue)}>
            <input
              id="phone"
              className={styles.floatingInput}
              value={phone}
              onChange={event => setPhone(event.target.value)}
              type="tel"
              autoComplete="tel"
              required
            />
            <label className={styles.floatingLabel} htmlFor="phone">
              手机号
            </label>
          </div>

          <div className={clsx(styles.floatingField, Boolean(deliveryEmail) && styles.hasValue)}>
            <input
              id="deliveryEmail"
              className={styles.floatingInput}
              value={deliveryEmail}
              onChange={event => setDeliveryEmail(event.target.value)}
              type="email"
              autoComplete="email"
            />
            <label className={styles.floatingLabel} htmlFor="deliveryEmail">
              邮箱 (选填)
            </label>
          </div>

          <div className={styles.otpSection}>
            <div className={styles.otpHeader}>
              <span className={styles.otpTitle}>验证码</span>
              <button
                type="button"
                className={styles.codeButtonInline}
                disabled={sendingCode || countdown > 0}
                onClick={handleSendCode}
              >
                {countdown > 0 ? `${countdown}s 后重新获取` : "获取验证码"}
              </button>
            </div>

            <div className={styles.otpGrid}>
              {digits.map((digit, index) => (
                <input
                  key={index}
                  ref={el => (inputsRef.current[index] = el)}
                  className={clsx(
                    styles.otpCell,
                    Boolean(digit) && styles.otpCellFilled
                  )}
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  maxLength={6}
                  value={digit}
                  autoComplete={index === 0 ? "one-time-code" : "off"}
                  onChange={e => handleDigitChange(index, e)}
                  onKeyDown={e => handleKeyDown(index, e)}
                  onPaste={handlePaste}
                  aria-label={`验证码第 ${index + 1} 位`}
                />
              ))}
            </div>
          </div>

          {error ? <div className={styles.error}>{error}</div> : null}

          <div className={styles.actions}>
            <button type="submit" className={styles.submitButton} disabled={isDisabled}>
              {submitting ? "登录中..." : "登录"}
            </button>
            <div className={styles.switchLink}>
              还没有账号？
              <button
                type="button"
                className={styles.linkButton}
                onClick={() => navigate("/register", { state: { from } })}
              >
                前往注册
              </button>
            </div>
          </div>
        </form>

        <div className={styles.divider}>
          <span>或通过以下方式登录</span>
        </div>

        <div className={styles.oauthRow}>
          <button type="button" className={styles.githubButton} onClick={handleGitHubLogin}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z" />
            </svg>
            GitHub 登录
          </button>
          <button type="button" className={styles.campusButton} onClick={handleCampusLogin}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M22 10v6M2 10l10-5 10 5-10 5z" />
              <path d="M6 12v5c3 3 9 3 12 0v-5" />
            </svg>
            校园账号登录
          </button>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;

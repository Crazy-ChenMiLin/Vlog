import { FormEvent, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import clsx from "clsx";
import { authService } from "@/services/authService";
import { useAuth } from "@/context/AuthContext";
import type { RegisterRequest } from "@/types/auth";
import styles from "./RegisterPage.module.css";

const OTP_LENGTH = 6;

const RegisterPage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { register } = useAuth();
  const [phone, setPhone] = useState("");
  const [deliveryEmail, setDeliveryEmail] = useState("");
  const [digits, setDigits] = useState<string[]>(Array(OTP_LENGTH).fill(""));
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const redirectTimerRef = useRef<number | null>(null);
  const inputsRef = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown(prev => prev - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  useEffect(() => {
    return () => {
      if (redirectTimerRef.current) {
        window.clearTimeout(redirectTimerRef.current);
      }
    };
  }, []);

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

  const handleSendCode = async () => {
    if (!phone) {
      setError("请先填写手机号");
      return;
    }
    setError(null);
    setMessage(null);
    setSendingCode(true);
    try {
      await authService.sendCode({
        scene: "REGISTER",
        identifierType: "PHONE",
        identifier: phone,
        deliveryEmail: deliveryEmail || undefined
      });
      setMessage(deliveryEmail ? "验证码已发送到接收邮箱，请注意查收" : "验证码已发送");
      setCountdown(60);
      inputsRef.current[0]?.focus();
    } catch (err) {
      const info = err instanceof Error ? err.message : "验证码发送失败";
      setError(info);
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (code.length < OTP_LENGTH) {
      setError(`请输入完整的 ${OTP_LENGTH} 位验证码`);
      return;
    }
    setError(null);
    setMessage(null);
    setSubmitting(true);
    try {
      const payload: RegisterRequest = {
        identifierType: "PHONE",
        identifier: phone,
        code,
        password,
        agreeTerms
      };
      await register(payload);
      setMessage("注册成功，已自动登录");
      const from = (location.state as { from?: string } | undefined)?.from ?? "/";
      redirectTimerRef.current = window.setTimeout(() => {
        navigate(from, { replace: true });
      }, 400);
    } catch (err) {
      const info = err instanceof Error ? err.message : "注册失败，请稍后重试";
      setError(info);
    } finally {
      setSubmitting(false);
    }
  };

  const isDisabled = submitting || !phone || code.length < OTP_LENGTH || !password || !agreeTerms;

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
          <h1 className={styles.title}>加入知光</h1>
          <p className={styles.subtitle}>完成注册，与更多人分享你的知识</p>
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

          <div className={styles.emailWrapper}>
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
                接收邮箱 (选填)
              </label>
            </div>
            <span className={styles.tips}>邮箱只用于接收本次验证码，不会写入账号身份</span>
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

          <div className={clsx(styles.floatingField, Boolean(password) && styles.hasValue)}>
            <input
              id="password"
              className={styles.floatingInput}
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              autoComplete="new-password"
              required
            />
            <label className={styles.floatingLabel} htmlFor="password">
              设置登录密码 (不少于 8 位)
            </label>
          </div>

          <div className={styles.checkboxRow}>
            <input
              id="agreeTerms"
              className={styles.checkbox}
              type="checkbox"
              checked={agreeTerms}
              onChange={event => setAgreeTerms(event.target.checked)}
            />
            <label className={styles.termsLabel} htmlFor="agreeTerms">
              我已阅读并同意
              <a href="#" onClick={e => e.preventDefault()}>《用户协议》</a>
              与
              <a href="#" onClick={e => e.preventDefault()}>《隐私政策》</a>
            </label>
          </div>

          {error ? <div className={styles.error}>{error}</div> : null}
          {message ? <div className={styles.success}>{message}</div> : null}

          <div className={styles.actions}>
            <button type="submit" className={styles.submitButton} disabled={isDisabled}>
              {submitting ? "注册中..." : "立即注册"}
            </button>
            <div className={styles.switchLink}>
              已有账号？
              <button
                type="button"
                className={styles.linkButton}
                onClick={() => navigate("/login")}
              >
                前往登录
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
};

export default RegisterPage;

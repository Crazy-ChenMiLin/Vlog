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
  const [email, setEmail] = useState("");
  const [digits, setDigits] = useState<string[]>(Array(OTP_LENGTH).fill(""));
  const [password, setPassword] = useState("");
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [lastCodeEmail, setLastCodeEmail] = useState("");
  const [countdown, setCountdown] = useState(0);
  const emailInputRef = useRef<HTMLInputElement>(null);
  const inputsRef = useRef<(HTMLInputElement | null)[]>([]);
  const from = (location.state as { from?: string } | undefined)?.from ?? "/";
  const identifier = email.trim().toLowerCase();
  const code = digits.join("");
  const currentCountdown = lastCodeEmail === identifier ? countdown : 0;
  const isDisabled = submitting || sendingCode || !identifier || code.length < OTP_LENGTH || !password.trim() || !agreeTerms;

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown(prev => prev - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  const handleEmailChange = (value: string) => {
    if (value.trim().toLowerCase() !== identifier) {
      setDigits(Array(OTP_LENGTH).fill(""));
      setMessage(null);
    }
    setEmail(value);
    setError(null);
  };

  const handlePaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pastedText = e.clipboardData.getData("text");
    const numericOnly = pastedText.replace(/\D/g, "").slice(0, OTP_LENGTH);
    if (!numericOnly) return;

    const newDigits = Array(OTP_LENGTH).fill("");
    for (let i = 0; i < OTP_LENGTH; i++) {
      newDigits[i] = numericOnly[i] || "";
    }
    setDigits(newDigits);

    const focusTarget = Math.min(numericOnly.length, OTP_LENGTH - 1);
    inputsRef.current[focusTarget]?.focus();
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace") {
      e.preventDefault();
      const newDigits = [...digits];
      if (newDigits[index]) {
        newDigits[index] = "";
        setDigits(newDigits);
      } else if (index > 0) {
        newDigits[index - 1] = "";
        setDigits(newDigits);
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
      setDigits(newDigits);
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
      setDigits(newDigits);
      const nextIndex = Math.min(index + numeric.length - 1, OTP_LENGTH - 1);
      inputsRef.current[nextIndex]?.focus();
      return;
    }

    const singleDigit = numeric[numeric.length - 1];
    const newDigits = [...digits];
    newDigits[index] = singleDigit;
    setDigits(newDigits);

    if (index < OTP_LENGTH - 1) {
      inputsRef.current[index + 1]?.focus();
    }
  };

  const handleSendCode = async () => {
    if (!emailInputRef.current?.reportValidity()) return;
    setError(null);
    setMessage(null);
    setSendingCode(true);
    try {
      await authService.sendCode({
        scene: "REGISTER",
        identifierType: "EMAIL",
        identifier
      });
      setMessage("验证码已发送，请查收邮件");
      setLastCodeEmail(identifier);
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
    if (isDisabled) return;
    const trimmedPassword = password.trim();
    if (trimmedPassword.length < 8 || !/\p{L}/u.test(trimmedPassword) || !/\p{Nd}/u.test(trimmedPassword)) {
      setError("密码至少 8 位，并包含字母和数字");
      setMessage(null);
      return;
    }
    setError(null);
    setMessage(null);
    setSubmitting(true);
    try {
      const payload: RegisterRequest = {
        identifierType: "EMAIL",
        identifier,
        code,
        password,
        agreeTerms
      };
      await register(payload);
      navigate(from, { replace: true });
    } catch (err) {
      const info = err instanceof Error ? err.message : "注册失败，请稍后重试";
      setError(info);
    } finally {
      setSubmitting(false);
    }
  };

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

        <form className={styles.form} onSubmit={handleSubmit}>
          <div className={clsx(styles.floatingField, Boolean(email) && styles.hasValue)}>
            <input
              id="registerEmail"
              name="email"
              ref={emailInputRef}
              className={styles.floatingInput}
              value={email}
              onChange={event => handleEmailChange(event.target.value)}
              type="email"
              autoComplete="username"
              readOnly={sendingCode || submitting}
              required
            />
            <label className={styles.floatingLabel} htmlFor="registerEmail">
              邮箱
            </label>
          </div>

          <div className={styles.otpSection}>
            <div className={styles.otpHeader}>
              <span className={styles.otpTitle}>验证码</span>
              <button
                type="button"
                className={styles.codeButtonInline}
                disabled={sendingCode || submitting || currentCountdown > 0}
                onClick={handleSendCode}
              >
                {sendingCode ? "发送中…" : currentCountdown > 0 ? `${currentCountdown}s 后重新获取` : "获取验证码"}
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

          <div className={styles.passwordField}>
            <div className={clsx(styles.floatingField, Boolean(password) && styles.hasValue)}>
              <input
                id="password"
                name="password"
                className={styles.floatingInput}
                type="password"
                value={password}
                onChange={event => setPassword(event.target.value)}
                autoComplete="new-password"
                aria-describedby="passwordRequirements"
                required
              />
              <label className={styles.floatingLabel} htmlFor="password">
                设置密码
              </label>
            </div>
            <span className={styles.tips} id="passwordRequirements">至少 8 位，包含字母和数字</span>
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

          {error ? <div className={styles.error} role="alert">{error}</div> : null}
          {message ? <div className={styles.success} role="status">{message}</div> : null}

          <div className={styles.actions}>
            <button type="submit" className={styles.submitButton} disabled={isDisabled}>
              {submitting ? "注册中..." : "立即注册"}
            </button>
            <div className={styles.switchLink}>
              已有账号？
              <button
                type="button"
                className={styles.linkButton}
                onClick={() => navigate("/login", { state: { from } })}
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

import { FormEvent, useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "@/context/AuthContext";
import type { LoginRequest } from "@/types/auth";
import { authService } from "@/services/authService";
import styles from "./LoginPage.module.css";

type LocationState = {
  from?: string;
};

const LoginPage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { login, isLoading, user } = useAuth();
  const [phone, setPhone] = useState("");
  const [deliveryEmail, setDeliveryEmail] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);

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

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
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

  const handleSendCode = async () => {
    if (!phone) {
      setError("请先填写手机号");
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
    } catch (err) {
      const info = err instanceof Error ? err.message : "验证码发送失败";
      setError(info);
    } finally {
      setSendingCode(false);
    }
  };

  const isDisabled = submitting || !phone || !code;

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.titleBlock}>
          <h1 className={styles.title}>欢迎回来</h1>
          <p className={styles.subtitle}>登录知光，与知识发光</p>
        </div>

        <form className={styles.form} onSubmit={handleSubmit}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="phone">手机号</label>
            <input
              id="phone"
              className={styles.input}
              value={phone}
              onChange={event => setPhone(event.target.value)}
              placeholder="请输入手机号"
              type="tel"
              autoComplete="tel"
            />
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="deliveryEmail">验证码接收邮箱</label>
            <input
              id="deliveryEmail"
              className={styles.input}
              value={deliveryEmail}
              onChange={event => setDeliveryEmail(event.target.value)}
              placeholder="可选：把验证码发送到这个邮箱"
              type="email"
              autoComplete="email"
            />
            <span className={styles.tips}>邮箱只用于接收本次验证码，不作为登录账号。</span>
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="code">验证码</label>
            <div className={styles.codeRow}>
              <input
                id="code"
                className={styles.input}
                value={code}
                onChange={event => setCode(event.target.value)}
                placeholder="请输入验证码"
                autoComplete="one-time-code"
              />
              <button
                type="button"
                className={styles.codeButton}
                disabled={sendingCode || countdown > 0}
                onClick={handleSendCode}
              >
                {countdown > 0 ? `${countdown}s` : "获取验证码"}
              </button>
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
                style={{ background: "none", border: "none", color: "var(--color-primary-strong)", fontWeight: 600, cursor: "pointer" }}
                onClick={() => navigate("/register", { state: { from } })}
              >
                前往注册
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
};

export default LoginPage;

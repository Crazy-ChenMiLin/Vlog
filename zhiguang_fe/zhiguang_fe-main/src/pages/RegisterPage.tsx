import { FormEvent, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { authService } from "@/services/authService";
import { useAuth } from "@/context/AuthContext";
import type { RegisterRequest } from "@/types/auth";
import styles from "./RegisterPage.module.css";

const RegisterPage = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { register } = useAuth();
  const [phone, setPhone] = useState("");
  const [deliveryEmail, setDeliveryEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const redirectTimerRef = useRef<number | null>(null);

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
      setMessage(deliveryEmail ? "验证码已发送到接收邮箱，请注意查收" : "验证码已生成，请查看后端日志");
      setCountdown(60);
    } catch (err) {
      const info = err instanceof Error ? err.message : "验证码发送失败";
      setError(info);
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
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

  const isDisabled = submitting || !phone || !code || !password || !agreeTerms;

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.titleBlock}>
          <h1 className={styles.title}>加入知光</h1>
          <p className={styles.subtitle}>完成注册，与更多人分享你的知识</p>
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
            <span className={styles.tips}>邮箱只用于接收本次验证码，不会写入账号身份。</span>
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

          <div className={styles.field}>
            <label className={styles.label} htmlFor="password">登录密码</label>
            <input
              id="password"
              className={styles.input}
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              placeholder="请设置不少于 8 位的密码"
              autoComplete="new-password"
            />
          </div>

          <div className={styles.field}>
            <div className={styles.checkboxRow}>
              <input
                id="agreeTerms"
                type="checkbox"
                checked={agreeTerms}
                onChange={event => setAgreeTerms(event.target.checked)}
              />
              <label className={styles.label} htmlFor="agreeTerms">
                我已阅读并同意
                <a href="#" onClick={e => e.preventDefault()}>《用户协议》</a>
                和
                <a href="#" onClick={e => e.preventDefault()}>《隐私政策》</a>
              </label>
            </div>
          </div>

          {error ? <div className={styles.error}>{error}</div> : null}
          {message ? <div className={styles.success}>{message}</div> : null}

          <div className={styles.actions}>
            <button type="submit" className={styles.submitButton} disabled={isDisabled}>
              {submitting ? "注册中..." : "立即注册"}
            </button>
            <div className={styles.switchLink}>
              已有账号？
              <button type="button" onClick={() => navigate("/login")}>返回登录</button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
};

export default RegisterPage;

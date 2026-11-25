import { useCallback, useEffect, useRef, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { useAuth } from "@/context/AuthContext";
import { ApiError, apiFetch } from "@/services/apiClient";
import styles from "./VipPage.module.css";

type PurchaseStatus = {
  decision: "ALLOW" | "PURCHASE_REQUIRED" | "DENY";
  product_name?: string;
  reason_code?: string;
  capability_status?: string;
  purchase_url?: string;
  purchase_qr_code?: string;
  charging_options?: { sku_id: string; display_name: string; price: string }[];
};

function safeLink(url?: string, protocols: string[] = ["https:", "alipays:"]) {
  if (!url) return undefined;
  try { return protocols.includes(new URL(url).protocol) ? url : undefined; }
  catch { return undefined; }
}

export default function VipPage() {
  const { user, isLoading, tokens } = useAuth();
  const [status, setStatus] = useState<PurchaseStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const requestId = useRef(0);
  const accessToken = tokens?.accessToken;

  const refresh = useCallback(async () => {
    if (!accessToken) return;
    const current = ++requestId.current;
    setBusy(true);
    setError("");
    try {
      const next = await apiFetch<PurchaseStatus>("/api/v1/payment/consult", { accessToken });
      if (current === requestId.current) setStatus(next);
    } catch (err) {
      if (current !== requestId.current) return;
      setStatus(null);
      setError(err instanceof ApiError && err.status === 401
        ? "登录状态已过期，请重新登录。"
        : "购买状态暂时无法查询，请稍后重试。");
    } finally {
      if (current === requestId.current) setBusy(false);
    }
  }, [accessToken]);

  useEffect(() => {
    if (user && accessToken) void refresh();
    return () => { requestId.current++; };
  }, [user?.id, accessToken, refresh]);

  useEffect(() => {
    const onFocus = () => { if (user && accessToken) void refresh(); };
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [user?.id, accessToken, refresh]);

  if (isLoading) return <main className={styles.page}>正在加载登录状态…</main>;
  if (!user || !accessToken) return <Navigate to="/login" state={{ from: "/vip" }} replace />;

  const purchasing = status?.decision === "PURCHASE_REQUIRED" && status.capability_status === "ENABLED";
  const payUrl = purchasing ? safeLink(status?.purchase_url) : undefined;
  const qrUrl = purchasing ? safeLink(status?.purchase_qr_code, ["https:"]) : undefined;
  return (
    <main className={styles.page}>
      <div className={styles.shell}>
        <Link className={styles.back} to="/">← 返回知光</Link>
        <section className={styles.card}>
          <div className={styles.content}>
            <span className={styles.eyebrow}>ZHIGUANG MEMBERSHIP</span>
            <h1>知光 VIP</h1>
            <p className={styles.subtitle}>一次购买，长期拥有。</p>
            <div className={styles.divider} />
            <p className={styles.account}>当前账号：<strong>{user.nickname || user.phone || user.email || "知光用户"}</strong></p>
            <div aria-live="polite" className={styles.status}>
              {busy && <p>正在查询购买状态…</p>}
              {error && <p className={styles.error} role="alert">{error}</p>}
              {!busy && !error && status?.decision === "ALLOW" && <>
                <span className={styles.badge}>已开通</span>
                <h2>你已经是知光 VIP</h2>
                <p>感谢你的支持，购买权益已确认。</p>
              </>}
              {!busy && !error && status?.decision === "DENY" && <>
                <h2>暂时无法开通</h2>
                <p>当前权益或商品状态暂不可用，请稍后重试。</p>
              </>}
              {!busy && !error && status?.decision === "PURCHASE_REQUIRED" && <>
                <span className={styles.badge}>永久买断</span>
                <h2>{status.product_name || "知光 VIP"}</h2>
                {status.charging_options?.map(option => (
                  <div className={styles.price} key={option.sku_id}>
                    <span>{option.display_name}</span><strong>¥{option.price}</strong>
                  </div>
                ))}
                {!purchasing && <p>商品暂未开放购买，请稍后再试。</p>}
                {purchasing && (payUrl || qrUrl) && <>
                  <div className={styles.actions}>
                    {payUrl && <a className={styles.primary} href={payUrl}>打开支付宝购买</a>}
                    {qrUrl && <a className={styles.secondary} href={qrUrl} target="_blank" rel="noreferrer">打开支付宝扫码入口</a>}
                  </div>
                  <p className={styles.hint}>使用支付宝完成支付后返回此页，点击下方按钮确认权益。</p>
                </>}
                {purchasing && !payUrl && !qrUrl && <p>暂时没有获取到购买入口，请刷新后重试。</p>}
              </>}
            </div>
            <button className={styles.refresh} type="button" disabled={busy} onClick={() => void refresh()}>
              {busy ? "查询中…" : "刷新购买状态"}
            </button>
          </div>
          <aside className={styles.aside}>
            <span>✦</span><p>为每一次<br />知识的积累</p>
            <small>支付由支付宝安全处理</small>
          </aside>
        </section>
      </div>
    </main>
  );
}

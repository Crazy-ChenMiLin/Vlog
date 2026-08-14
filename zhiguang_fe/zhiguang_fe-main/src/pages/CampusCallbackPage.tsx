import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "@/context/AuthContext";

/**
 * 校园账号（CQUT-Auth OIDC）回调页。
 * <p>
 * 校园认证授权后回跳到 /callback/campus?code=xxx&state=xxx，此页面取出 code 与 state
 * 调后端换 JWT，成功后跳首页，失败后显示错误并提供返回登录按钮。
 */
const CampusCallbackPage = () => {
  const navigate = useNavigate();
  const { loginWithCampusCode } = useAuth();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    if (!code || !state) {
      navigate("/login", { replace: true });
      return;
    }
    loginWithCampusCode(code, state)
      .then(() => navigate("/", { replace: true }))
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : "校园账号登录失败");
      });
  }, [loginWithCampusCode, navigate]);

  if (error) {
    return (
      <div style={{ textAlign: "center", padding: "80px 20px" }}>
        <p style={{ color: "var(--color-danger, #c62828)", marginBottom: "16px" }}>
          {error}
        </p>
        <button
          onClick={() => navigate("/login")}
          style={{
            padding: "10px 24px",
            cursor: "pointer",
            border: "1px solid var(--color-border, #ddd)",
            borderRadius: "6px",
            background: "transparent"
          }}
        >
          返回登录
        </button>
      </div>
    );
  }

  return (
    <div style={{ textAlign: "center", padding: "80px 20px", color: "var(--color-text-secondary, #666)" }}>
      校园账号登录中...
    </div>
  );
};

export default CampusCallbackPage;

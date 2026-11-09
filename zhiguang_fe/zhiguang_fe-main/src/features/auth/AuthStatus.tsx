import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import UserBadge from "@/components/common/UserBadge";
import { useAuth } from "@/context/AuthContext";
import { isMockEnabled, setMockEnabled } from "@/mock/mockData";
import styles from "./AuthStatus.module.css";

const AuthStatus = () => {
  const { user, logout, isLoading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [mockActive, setMockActive] = useState<boolean>(isMockEnabled);

  const toggleMock = () => {
    const next = !mockActive;
    setMockActive(next);
    setMockEnabled(next);
    window.location.reload();
  };

  const mockButton = (
    <button
      type="button"
      className={styles.mockBadge}
      onClick={toggleMock}
      title="点击随时切换 Mock 演示数据 / 真实后端联调"
    >
      <span className={mockActive ? styles.mockDotActive : styles.mockDotInactive} />
      {mockActive ? "Mock模式" : "联调模式"}
    </button>
  );

  if (isLoading) {
    return (
      <div className={styles.wrapper}>
        {mockButton}
        <div style={{ color: "var(--color-text-muted)", fontSize: 13 }}>加载中...</div>
      </div>
    );
  }

  if (!user) {
    return (
      <div className={styles.wrapper}>
        {mockButton}
        <button
          type="button"
          className="ghost-button"
          onClick={() =>
            navigate("/login", {
              replace: false,
              state: { from: location.pathname + location.search + location.hash }
            })
          }
        >
          登录
        </button>
      </div>
    );
  }

  const displayName = user.nickname || "用户";
  const avatarUrl = user.avatar || undefined;

  const handleLogout = async () => {
    try {
      await logout();
    } catch (error) {
      console.error("退出登录失败", error);
    }
  };

  return (
    <div className={styles.wrapper}>
      {mockButton}
      <UserBadge name={displayName} avatarUrl={avatarUrl} />
      <button type="button" className={styles.logoutButton} onClick={handleLogout}>
        退出
      </button>
    </div>
  );
};

export default AuthStatus;

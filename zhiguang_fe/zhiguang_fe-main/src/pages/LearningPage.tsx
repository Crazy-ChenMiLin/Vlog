import { Link } from "react-router-dom";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import AuthStatus from "@/features/auth/AuthStatus";
import styles from "./LearningPage.module.css";

const LearningPage = () => {
  return (
    <AppLayout
      header={
        <MainHeader
          headline="我的学习"
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <div className={styles.wrapper}>
        <div className={styles.emptyCard}>
          <div className={styles.header}>
            <div className={styles.iconBadge}>📚</div>
            <h2 className={styles.title}>还没有购买内容</h2>
            <p className={styles.description}>
              微信扫码赞赏支持作者创作，开通专属知识专栏与学习特权
            </p>
          </div>

          <div className={styles.qrCard}>
            <div className={styles.qrImageWrap}>
              <img
                src="/pay-qrcode.jpg"
                alt="微信支付赞赏码"
                className={styles.qrImage}
              />
            </div>
            <div className={styles.qrTips}>
              <div className={styles.tipTag}>
                <span className={styles.wechatDot} />
                推荐使用微信扫码
              </div>
              <p className={styles.tipNote}>
                扫码支付时可备注您的知光账号，享受专属答疑与专栏特权
              </p>
            </div>
          </div>

          <div className={styles.actions}>
            <Link to="/" className={styles.primaryBtn}>
              前往首页探索
            </Link>
            <button
              type="button"
              className={styles.refreshBtn}
              onClick={() => window.location.reload()}
            >
              已支付？刷新查看
            </button>
          </div>
        </div>
      </div>
    </AppLayout>
  );
};

export default LearningPage;

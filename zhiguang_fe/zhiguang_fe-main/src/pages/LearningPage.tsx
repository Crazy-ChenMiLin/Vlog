import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
// 移除对 mock 数据文件的依赖，改为本地常量
const learningEmptyState = {
  title: "还没有购买内容",
  description: "去首页探索优质内容吧",
  actionLabel: "前往首页"
};
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
    >
      <div className={styles.emptyCard}>
        <div className={styles.icon}>📚</div>
        <div className={styles.title}>{learningEmptyState.title}</div>
        <div className={styles.description}>{learningEmptyState.description}</div>
        <button type="button" className="ghost-button">
          {learningEmptyState.actionLabel}
        </button>
      </div>
    </AppLayout>
  );
};

export default LearningPage;

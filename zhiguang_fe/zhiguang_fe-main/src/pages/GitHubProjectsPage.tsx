import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import { ExternalLinkIcon } from "@/components/icons/Icon";
import AuthStatus from "@/features/auth/AuthStatus";
import styles from "./GitHubProjectsPage.module.css";

const projects = [
  {
    name: "知光知识社区",
    repo: "Crazy-ChenMiLin/Vlog",
    description: "知光的前后端代码。包含知文发布、搜索、知识问答和 VIP 权益。"
  },
  {
    name: "LDStatusPro",
    repo: "caigg188/LDStatusPro",
    description: "参考项目。包含 Linux.do 社区辅助脚本、AI 帖子总结和 LD 士多前端。"
  }
];

export default function GitHubProjectsPage() {
  return (
    <AppLayout
      header={<MainHeader headline="GitHub 项目导览" rightSlot={<AuthStatus />} />}
      variant="cardless"
    >
      <ul className={styles.projects}>
        {projects.map(({ name, repo, description }) => (
          <li key={repo}>
            <a
              className={styles.project}
              href={`https://github.com/${repo}`}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={`在新标签页打开 ${name} 的 GitHub 仓库`}
            >
              <div>
                <h2>{name}</h2>
                <p className={styles.repo}>{repo}</p>
                <p className={styles.description}>{description}</p>
              </div>
              <ExternalLinkIcon className={styles.arrow} width={18} height={18} aria-hidden="true" />
            </a>
          </li>
        ))}
      </ul>
    </AppLayout>
  );
}

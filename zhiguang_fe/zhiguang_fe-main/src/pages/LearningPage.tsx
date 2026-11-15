import { useEffect, useState } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import AuthStatus from "@/features/auth/AuthStatus";
import styles from "./LearningPage.module.css";

interface DonationRecord {
  id: string;
  name: string;
  amount: number;
  message: string;
  date: string;
}

const STORAGE_KEY = "zhiguang_donation_records_real";

const LearningPage = () => {
  const [activeTab, setActiveTab] = useState<"donate" | "records">("donate");
  const [records, setRecords] = useState<DonationRecord[]>(() => {
    try {
      localStorage.removeItem("zhiguang_donation_records");
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        if (Array.isArray(parsed)) return parsed;
      }
    } catch {}
    return [];
  });

  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState("");
  const [formAmount, setFormAmount] = useState("");
  const [formMessage, setFormMessage] = useState("");

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
    } catch {}
  }, [records]);

  const totalAmount = records.reduce((acc, cur) => acc + cur.amount, 0);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const amountNum = parseFloat(formAmount);
    if (!amountNum || amountNum <= 0) return;

    const newRecord: DonationRecord = {
      id: String(Date.now()),
      name: formName.trim() || "匿名同学",
      amount: Math.round(amountNum * 100) / 100,
      message: formMessage.trim() || "心意支持！",
      date: new Date().toISOString().slice(0, 10)
    };

    setRecords([newRecord, ...records]);
    setFormName("");
    setFormAmount("");
    setFormMessage("");
    setShowForm(false);
    setActiveTab("records");
  };

  return (
    <AppLayout
      header={
        <MainHeader
          headline="打赏与支持"
          subtitle="感谢每一位支持知光创作与开源的朋友"
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <div className={styles.wrapper}>
        <div className={styles.mainCard}>
          <div className={styles.tabNav}>
            <button
              type="button"
              className={activeTab === "donate" ? styles.tabActive : styles.tab}
              onClick={() => setActiveTab("donate")}
            >
              ☕ 扫码打赏
            </button>
            <button
              type="button"
              className={activeTab === "records" ? styles.tabActive : styles.tab}
              onClick={() => setActiveTab("records")}
            >
              📜 打赏记录 ({records.length})
            </button>
          </div>

          {activeTab === "donate" ? (
            <div className={styles.donateSection}>
              <div className={styles.qrCard}>
                <div className={styles.qrImageWrap}>
                  <img
                    src="/pay-qrcode.jpg"
                    alt="微信支付打赏码"
                    className={styles.qrImage}
                  />
                </div>
                <div className={styles.qrTag}>
                  <span className={styles.wechatDot} />
                  推荐使用微信扫码打赏
                </div>
              </div>

              <div className={styles.amounts}>
                <div className={styles.amountPill}>☕ ￥6.66 一杯咖啡</div>
                <div className={styles.amountPill}>🥐 ￥18.88 助力创作</div>
                <div className={styles.amountPill}>💖 ￥66.66 大力支持</div>
                <div className={styles.amountPill}>✨ 任意金额 心意无价</div>
              </div>

              <div className={styles.donateFooter}>
                <button
                  type="button"
                  className={styles.recordBtn}
                  onClick={() => {
                    setActiveTab("records");
                    setShowForm(true);
                  }}
                >
                  已完成打赏？点击登记鸣谢
                </button>
              </div>
            </div>
          ) : (
            <div className={styles.recordsSection}>
              <div className={styles.statsBar}>
                <div className={styles.statBox}>
                  <span className={styles.statLabel}>累计打赏</span>
                  <span className={styles.statValue}>¥{totalAmount.toFixed(2)}</span>
                </div>
                <div className={styles.statBox}>
                  <span className={styles.statLabel}>支持笔数</span>
                  <span className={styles.statValue}>{records.length} 次</span>
                </div>
                <button
                  type="button"
                  className={styles.addRecordBtn}
                  onClick={() => setShowForm(!showForm)}
                >
                  {showForm ? "收起登记" : "+ 登记打赏"}
                </button>
              </div>

              {showForm && (
                <form className={styles.form} onSubmit={handleSubmit}>
                  <div className={styles.formRow}>
                    <input
                      type="text"
                      className={styles.input}
                      placeholder="您的昵称 (选填)"
                      value={formName}
                      onChange={(e) => setFormName(e.target.value)}
                      maxLength={20}
                    />
                    <input
                      type="number"
                      step="0.01"
                      min="0.01"
                      className={styles.input}
                      placeholder="打赏金额 (元)"
                      value={formAmount}
                      onChange={(e) => setFormAmount(e.target.value)}
                      required
                    />
                  </div>
                  <input
                    type="text"
                    className={styles.input}
                    placeholder="附言留言 (选填，例如：加油！)"
                    value={formMessage}
                    onChange={(e) => setFormMessage(e.target.value)}
                    maxLength={60}
                  />
                  <button type="submit" className={styles.submitBtn}>
                    确认提交登记
                  </button>
                </form>
              )}

              <div className={styles.recordList}>
                {records.length === 0 ? (
                  <div className={styles.emptyWrap}>
                    <p className={styles.emptyText}>暂无打赏记录</p>
                    <p className={styles.emptySubText}>微信扫码支持后，点击上方“+ 登记打赏”留下您的心意</p>
                  </div>
                ) : (
                  records.map((item) => (
                    <div key={item.id} className={styles.recordItem}>
                      <div className={styles.recordLeft}>
                        <div className={styles.avatar}>
                          {item.name.slice(0, 1) || "知"}
                        </div>
                        <div className={styles.recordInfo}>
                          <div className={styles.recordName}>{item.name}</div>
                          <div className={styles.recordMsg}>{item.message}</div>
                        </div>
                      </div>
                      <div className={styles.recordRight}>
                        <span className={styles.amount}>+¥{item.amount.toFixed(2)}</span>
                        <span className={styles.date}>{item.date}</span>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </AppLayout>
  );
};

export default LearningPage;

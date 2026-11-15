import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import AuthStatus from "@/features/auth/AuthStatus";
import { useAuth } from "@/context/AuthContext";
import { computeSha256, knowpostService, uploadToPresigned } from "@/services/knowpostService";
import styles from "./CreatePage.module.css";

const SUGGESTED_TAGS = [
  "喂饱你的相册贪吃蛇",
  "负鼠表情包",
  "原创表情包",
  "热门表情包",
  "表情包制作",
  "表情包",
  "知光精选",
  "AI大模型",
  "架构设计"
];

const QUICK_EMOJIS = ["❤️", "👍", "🔥", "☕", "🌟", "👏", "😊", "🎉", "🥳", "🚀", "✨", "👀", "💯", "🐱", "🐾", "💡", "💪", "🙌"];

const MAX_IMAGES = 18;

const DefaultDevilGraphic = () => (
  <svg viewBox="0 0 100 100" width="80" height="80">
    <path d="M 22 28 C 12 18 10 36 24 38 Z" fill="#ff2442" />
    <path d="M 78 28 C 88 18 90 36 76 38 Z" fill="#ff2442" />
    <ellipse cx="50" cy="56" rx="34" ry="28" fill="#fdfaf3" stroke="#2d2b28" strokeWidth="2.5" />
    <ellipse cx="38" cy="50" rx="3" ry="4" fill="#2d2b28" />
    <ellipse cx="62" cy="50" rx="3" ry="4" fill="#2d2b28" />
    <path d="M 32 44 Q 38 41 44 44" fill="none" stroke="#2d2b28" strokeWidth="2" strokeLinecap="round" />
    <path d="M 56 44 Q 62 41 68 44" fill="none" stroke="#2d2b28" strokeWidth="2" strokeLinecap="round" />
    <path d="M 44 62 Q 50 67 56 62" fill="none" stroke="#2d2b28" strokeWidth="2" strokeLinecap="round" />
    <circle cx="28" cy="57" r="4.5" fill="#ffb4a2" opacity="0.6" />
    <circle cx="72" cy="57" r="4.5" fill="#ffb4a2" opacity="0.6" />
    <ellipse cx="50" cy="74" rx="12" ry="7" fill="#fdfaf3" stroke="#2d2b28" strokeWidth="2" />
  </svg>
);

const CreatePage = () => {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [showAllTags, setShowAllTags] = useState(false);
  const [showEmojiPicker, setShowEmojiPicker] = useState(false);
  const [showMoreSettings, setShowMoreSettings] = useState(true);
  const [visiblePublic, setVisiblePublic] = useState(true);

  const [uploadedImgUrls, setUploadedImgUrls] = useState<string[]>([]);
  const [imageUploading, setImageUploading] = useState(false);
  const [previewTab, setPreviewTab] = useState<"feed" | "detail">("feed");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [postId, setPostId] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const authorName = user?.nickname || user?.zhId || "ChenMiLin";

  const ensureDraft = async (): Promise<string> => {
    if (postId) return postId;
    const resp = await knowpostService.createDraft();
    const idStr = String(resp.id);
    setPostId(idStr);
    return idStr;
  };

  const handleSelectImages = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setError(null);
    setMessage(null);
    setImageUploading(true);

    const remaining = Math.max(0, MAX_IMAGES - uploadedImgUrls.length);
    if (remaining <= 0) {
      setError(`最多只可上传 ${MAX_IMAGES} 张图片`);
      setImageUploading(false);
      return;
    }

    const selectedList = Array.from(files).slice(0, remaining);

    const localPreviewUrls = selectedList.map(f => URL.createObjectURL(f));
    setUploadedImgUrls(prev => [...prev, ...localPreviewUrls]);

    try {
      const id = await ensureDraft();
      const uploadedRemoteUrls: string[] = [];

      for (const f of selectedList) {
        const match = f.name.match(/\.[^.]+$/);
        const ext = match ? match[0] : ".jpg";
        const contentType = f.type || "image/jpeg";
        const presign = await knowpostService.presign({
          scene: "knowpost_image",
          postId: id,
          contentType,
          ext
        });
        await uploadToPresigned(presign.putUrl, presign.headers, f);
        const publicUrl = presign.publicUrl ?? presign.putUrl.split("?")[0];
        uploadedRemoteUrls.push(publicUrl);
      }

      setUploadedImgUrls(prev => {
        const base = prev.slice(0, prev.length - localPreviewUrls.length);
        return [...base, ...uploadedRemoteUrls];
      });
    } catch {
      // 保留本地预览 URL
    } finally {
      setImageUploading(false);
    }
  };

  const handleDeleteImage = (index: number) => {
    setUploadedImgUrls(prev => prev.filter((_, i) => i !== index));
  };

  const handleClearImages = () => {
    setUploadedImgUrls([]);
  };

  const handleToggleTag = (tag: string) => {
    if (selectedTags.includes(tag)) {
      setSelectedTags(prev => prev.filter(t => t !== tag));
    } else {
      setSelectedTags(prev => [...prev, tag]);
      setContent(prev => (prev ? `${prev} #${tag}` : `#${tag}`));
    }
  };

  const handleAddEmoji = (emoji: string) => {
    setContent(prev => `${prev}${emoji}`);
    setShowEmojiPicker(false);
  };

  const handlePublish = async () => {
    setMessage(null);
    setError(null);

    if (!title.trim()) {
      setError("请填写笔记标题");
      return;
    }
    if (!content.trim()) {
      setError("请填写笔记正文描述");
      return;
    }

    setSubmitting(true);
    try {
      const id = await ensureDraft();
      const file = new File([content], "content.md", { type: "text/markdown" });
      const size = file.size;
      const sha256 = await computeSha256(file);
      const presign = await knowpostService.presign({
        scene: "knowpost_content",
        postId: id,
        contentType: "text/markdown",
        ext: ".md"
      });
      const { etag } = await uploadToPresigned(presign.putUrl, presign.headers, file);

      await knowpostService.confirmContent(id, {
        objectKey: presign.objectKey,
        etag,
        size,
        sha256
      });

      await knowpostService.update(id, {
        title: title.trim(),
        tags: selectedTags.length ? selectedTags : undefined,
        imgUrls: uploadedImgUrls.filter(u => !u.startsWith("blob:")),
        visible: visiblePublic ? "public" : "private",
        isTop: false,
        description: content.slice(0, 50)
      });

      await knowpostService.publish(id);
      setMessage("发布成功！正在跳转首页…");
      setTimeout(() => navigate("/"), 1200);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "发布失败，已为您保存草稿";
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleLeave = () => {
    try {
      localStorage.setItem("zhiguang_post_draft", JSON.stringify({
        title,
        content,
        selectedTags,
        date: new Date().toISOString()
      }));
    } catch {}
    navigate("/");
  };

  const visibleTags = showAllTags ? SUGGESTED_TAGS : SUGGESTED_TAGS.slice(0, 6);

  return (
    <AppLayout
      header={
        <MainHeader
          headline="发布图文笔记"
          subtitle="分享真实有价值的知识与灵感"
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <div className={styles.pageLayout}>
        <div className={styles.leftCol}>
          <div className={styles.card}>
            <div className={styles.imageCardHeader}>
              <div className={styles.imageTitleWrap}>
                <span>图片编辑</span>
                <span className={styles.imageCount}>{uploadedImgUrls.length}/{MAX_IMAGES}</span>
              </div>
              {uploadedImgUrls.length > 0 && (
                <button
                  type="button"
                  className={styles.trashBtn}
                  title="清空所有图片"
                  onClick={handleClearImages}
                >
                  🗑️
                </button>
              )}
            </div>

            <div className={styles.imageGrid}>
              <div
                className={styles.uploadBox}
                role="button"
                tabIndex={0}
                onClick={() => fileInputRef.current?.click()}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") fileInputRef.current?.click(); }}
              >
                +
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  accept="image/*"
                  className={styles.fileInput}
                  onChange={(e) => handleSelectImages(e.target.files)}
                />
              </div>

              {uploadedImgUrls.map((url, index) => (
                <div key={index} className={styles.imageThumbWrap}>
                  <img src={url} alt={`已上传 ${index + 1}`} className={styles.thumbImg} />
                  <span className={styles.indexBadge}>{index + 1}</span>
                  {index === 0 && <span className={styles.coverBadge}>封面</span>}
                  <button
                    type="button"
                    className={styles.deleteBtn}
                    onClick={() => handleDeleteImage(index)}
                  >
                    ✕
                  </button>
                </div>
              ))}
            </div>
          </div>

          <div className={styles.card}>
            <input
              type="text"
              className={styles.titleInput}
              placeholder="填写标题会有更多赞哦"
              maxLength={50}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />

            <textarea
              className={styles.bodyTextarea}
              placeholder="输入正文描述，真诚有价值的分享予人温暖"
              maxLength={1000}
              value={content}
              onChange={(e) => setContent(e.target.value)}
            />

            <div className={styles.tagsRow}>
              {visibleTags.map((tag) => {
                const isActive = selectedTags.includes(tag);
                return (
                  <button
                    key={tag}
                    type="button"
                    className={isActive ? `${styles.tagPill} ${styles.tagPillActive}` : styles.tagPill}
                    onClick={() => handleToggleTag(tag)}
                  >
                    #{tag}
                  </button>
                );
              })}
              <button
                type="button"
                className={styles.moreTagsBtn}
                onClick={() => setShowAllTags(!showAllTags)}
              >
                {showAllTags ? "收起 ^" : "更多 ⌄"}
              </button>
            </div>

            <div className={styles.editorToolbar}>
              <div className={styles.toolButtons}>
                <button
                  type="button"
                  className={styles.toolBtn}
                  onClick={() => setContent((prev) => `${prev} #话题`)}
                >
                  # 话题 ⌄
                </button>
                <button
                  type="button"
                  className={styles.toolBtn}
                  onClick={() => setContent((prev) => `${prev} @`)}
                >
                  @ 用户
                </button>
                <button
                  type="button"
                  className={styles.toolBtn}
                  onClick={() => setShowEmojiPicker(!showEmojiPicker)}
                >
                  😊 表情
                </button>

                {showEmojiPicker && (
                  <div className={styles.emojiPopover}>
                    {QUICK_EMOJIS.map((emoji) => (
                      <button
                        key={emoji}
                        type="button"
                        className={styles.emojiItem}
                        onClick={() => handleAddEmoji(emoji)}
                      >
                        {emoji}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              <span className={styles.wordCount}>{content.length}/1000</span>
            </div>
          </div>

          <div className={styles.settingsCard}>
            <div
              className={styles.settingsHeader}
              onClick={() => setShowMoreSettings(!showMoreSettings)}
            >
              <span className={styles.settingsTitle}>更多设置</span>
              <span className={styles.toggleText}>
                {showMoreSettings ? "收起 ^" : "展开 ⌄"}
              </span>
            </div>

            {showMoreSettings && (
              <div className={styles.settingsBody}>
                <div className={styles.settingItem}>
                  <div className={styles.settingLabelWrap}>
                    <span>👤</span>
                    <span>可见范围</span>
                  </div>
                  <select
                    className={styles.selectInput}
                    value={visiblePublic ? "public" : "private"}
                    onChange={(e) => setVisiblePublic(e.target.value === "public")}
                  >
                    <option value="public">公开可见</option>
                    <option value="private">仅自己可见</option>
                  </select>
                </div>
              </div>
            )}
          </div>

          {error && <div className={`${styles.alertBox} ${styles.alertError}`}>{error}</div>}
          {message && <div className={`${styles.alertBox} ${styles.alertSuccess}`}>{message}</div>}

          <div className={styles.bottomBar}>
            <button
              type="button"
              className={styles.leaveBtn}
              onClick={handleLeave}
            >
              暂存离开
            </button>
            <button
              type="button"
              className={styles.publishBtn}
              onClick={handlePublish}
              disabled={submitting || imageUploading}
            >
              {submitting ? "发布中…" : "发布"}
            </button>
          </div>
        </div>

        <div className={styles.rightCol}>
          <div className={styles.previewTabNav}>
            <button
              type="button"
              className={previewTab === "detail" ? `${styles.previewTabBtn} ${styles.previewTabBtnActive}` : styles.previewTabBtn}
              onClick={() => setPreviewTab("detail")}
            >
              笔记预览
            </button>
            <button
              type="button"
              className={previewTab === "feed" ? `${styles.previewTabBtn} ${styles.previewTabBtnActive}` : styles.previewTabBtn}
              onClick={() => setPreviewTab("feed")}
            >
              封面预览
            </button>
          </div>

          <div className={styles.phoneFrame}>
            <div className={styles.phoneScreen}>
              <div className={styles.phoneStatusBar}>
                <span>9:41</span>
                <span>📶 🛜 🔋</span>
              </div>

              <div className={styles.phoneTopNav}>
                <span>☰</span>
                <div className={styles.phoneTopTabs}>
                  <span>关注</span>
                  <span className={styles.phoneTabActive}>发现</span>
                  <span>附近</span>
                </div>
                <span>🔍</span>
              </div>

              <div className={styles.phoneSubNav}>
                <span className={styles.phoneSubActive}>推荐</span>
                <span>直播</span>
                <span>短剧</span>
                <span>穿搭</span>
                <span>旅行</span>
                <span>动漫</span>
              </div>

              <div className={styles.phoneBody}>
                {previewTab === "feed" ? (
                  <div className={styles.feedGrid}>
                    <div className={styles.feedCard}>
                      <div className={styles.feedCardImageWrap}>
                        {uploadedImgUrls[0] ? (
                          <img
                            src={uploadedImgUrls[0]}
                            alt="封面"
                            className={styles.feedCardImage}
                          />
                        ) : (
                          <DefaultDevilGraphic />
                        )}
                      </div>
                      <div className={styles.feedCardBody}>
                        <div className={styles.feedCardTitle}>
                          {title.trim() || "示例笔记标题1"}
                        </div>
                        <div className={styles.feedCardFooter}>
                          <div className={styles.feedAuthor}>
                            <div className={styles.feedAvatar}>
                              {authorName.slice(0, 1)}
                            </div>
                            <span>{authorName}</span>
                          </div>
                          <span className={styles.feedLike}>♡ 0</span>
                        </div>
                      </div>
                    </div>

                    <div className={styles.feedCard}>
                      <div className={styles.feedCardImageWrap}>
                        <div className={styles.placeholderCover} />
                      </div>
                      <div className={styles.feedCardBody}>
                        <div className={styles.feedCardTitle}>示例笔记标题1</div>
                        <div className={styles.feedCardFooter}>
                          <div className={styles.feedAuthor}>
                            <div className={styles.feedAvatar}>用</div>
                            <span>用户名</span>
                          </div>
                          <span className={styles.feedLike}>♡ 0</span>
                        </div>
                      </div>
                    </div>

                    <div className={styles.feedCard}>
                      <div className={styles.feedCardImageWrap}>
                        <div className={styles.placeholderCover} />
                      </div>
                      <div className={styles.feedCardBody}>
                        <div className={styles.feedCardTitle}>示例笔记标题2</div>
                        <div className={styles.feedCardFooter}>
                          <div className={styles.feedAuthor}>
                            <div className={styles.feedAvatar}>用</div>
                            <span>用户名</span>
                          </div>
                          <span className={styles.feedLike}>♡ 0</span>
                        </div>
                      </div>
                    </div>

                    <div className={styles.feedCard}>
                      <div className={styles.feedCardImageWrap}>
                        <div className={styles.placeholderCover} />
                      </div>
                      <div className={styles.feedCardBody}>
                        <div className={styles.feedCardTitle}>示例笔记标题3</div>
                        <div className={styles.feedCardFooter}>
                          <div className={styles.feedAuthor}>
                            <div className={styles.feedAvatar}>用</div>
                            <span>用户名</span>
                          </div>
                          <span className={styles.feedLike}>♡ 0</span>
                        </div>
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className={styles.detailView}>
                    <div className={styles.detailImageWrap}>
                      {uploadedImgUrls[0] ? (
                        <img
                          src={uploadedImgUrls[0]}
                          alt="主图"
                          className={styles.detailImage}
                        />
                      ) : (
                        <DefaultDevilGraphic />
                      )}
                    </div>
                    <div className={styles.detailContent}>
                      <h4 className={styles.detailTitle}>
                        {title.trim() || "示例笔记标题"}
                      </h4>
                      <p className={styles.detailDesc}>
                        {content.trim() || "输入正文描述，真诚有价值的分享予人温暖"}
                      </p>
                      {selectedTags.length > 0 && (
                        <div className={styles.detailTags}>
                          {selectedTags.map((tag) => (
                            <span key={tag} className={styles.detailTag}>
                              #{tag}
                            </span>
                          ))}
                        </div>
                      )}
                      <div className={styles.detailMeta}>刚刚 · 发布于 重庆</div>
                    </div>
                  </div>
                )}
              </div>

              <div className={styles.phoneBottomNav}>
                <span className={styles.phoneBottomActive}>首页</span>
                <span>市集</span>
                <span className={styles.phonePlusBtn}>+</span>
                <span>消息</span>
                <span>我</span>
              </div>
              <div className={styles.homeIndicator} />
            </div>
          </div>
        </div>
      </div>
    </AppLayout>
  );
};

export default CreatePage;

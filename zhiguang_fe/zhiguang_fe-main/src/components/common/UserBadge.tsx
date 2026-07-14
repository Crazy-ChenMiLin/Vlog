import { useEffect, useState } from "react";
import styles from "./UserBadge.module.css";

type UserBadgeProps = {
  name: string;
  alias?: string;
  avatarUrl?: string;
};

const getInitial = (value: string) => value.trim().charAt(0).toUpperCase() || "?";

const UserBadge = ({ name, alias, avatarUrl }: UserBadgeProps) => {
  const [avatarLoadFailed, setAvatarLoadFailed] = useState(false);

  useEffect(() => {
    setAvatarLoadFailed(false);
  }, [avatarUrl]);

  return (
    <div className={styles.badge}>
      <div className={styles.avatar}>
        {avatarUrl && !avatarLoadFailed ? (
          <img
            src={avatarUrl}
            alt="avatar"
            className={styles.avatarImg}
            onError={() => setAvatarLoadFailed(true)}
          />
        ) : (
          getInitial(name)
        )}
      </div>
      <div className={styles.meta}>
        <span className={styles.name}>{name}</span>
        {alias ? <span className={styles.alias}>{alias}</span> : null}
      </div>
    </div>
  );
};

export default UserBadge;

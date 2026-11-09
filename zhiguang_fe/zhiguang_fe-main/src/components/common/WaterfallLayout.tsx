import { ReactNode, useEffect, useRef, useState, useMemo } from "react";
import styles from "./WaterfallLayout.module.css";

export type WaterfallLayoutProps<T> = {
  items: T[];
  renderItem: (item: T, index: number) => ReactNode;
  getItemKey?: (item: T, index: number) => string | number;
  estimateHeight?: (item: T) => number;
  gap?: number;
  className?: string;
  columns?: number; // optional manual override
};

export const WaterfallLayout = <T,>({
  items,
  renderItem,
  getItemKey,
  estimateHeight,
  gap = 18,
  className,
  columns: manualColumns
}: WaterfallLayoutProps<T>) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [columnCount, setColumnCount] = useState<number>(() => {
    if (manualColumns) return manualColumns;
    if (typeof window === "undefined") return 3;
    const w = window.innerWidth;
    if (w < 680) return 1;
    if (w < 1040) return 2;
    return 3;
  });

  useEffect(() => {
    if (manualColumns) {
      setColumnCount(manualColumns);
      return;
    }

    const el = containerRef.current;
    if (!el) return;

    const computeColumns = (width: number) => {
      if (width < 640) return 1;
      if (width < 1000) return 2;
      return 3;
    };

    setColumnCount(computeColumns(el.offsetWidth));

    const ro = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const w = entry.contentRect.width;
        if (w > 0) {
          const nextCols = computeColumns(w);
          setColumnCount((prev) => (prev !== nextCols ? nextCols : prev));
        }
      }
    });

    ro.observe(el);
    return () => ro.disconnect();
  }, [manualColumns]);

  // 分发数据项到各列，采用贪心算法按预估高度分配到当前最矮列
  const columnsData = useMemo(() => {
    const cols: { item: T; originalIndex: number }[][] = Array.from(
      { length: columnCount },
      () => []
    );
    const colHeights = new Array(columnCount).fill(0);

    items.forEach((item, index) => {
      let targetCol = 0;
      for (let c = 1; c < columnCount; c++) {
        if (colHeights[c] < colHeights[targetCol]) {
          targetCol = c;
        }
      }
      cols[targetCol].push({ item, originalIndex: index });
      const h = estimateHeight ? estimateHeight(item) : 340;
      colHeights[targetCol] += h + gap;
    });

    return cols;
  }, [items, columnCount, estimateHeight, gap]);

  return (
    <div
      ref={containerRef}
      className={`${styles.waterfall} ${className ?? ""}`.trim()}
      style={{ gap: `${gap}px` }}
    >
      {columnsData.map((col, colIdx) => (
        <div
          key={colIdx}
          className={styles.column}
          style={{ gap: `${gap}px` }}
        >
          {col.map(({ item, originalIndex }) => {
            const key = getItemKey
              ? getItemKey(item, originalIndex)
              : originalIndex;
            return (
              <div key={key} className={styles.item}>
                {renderItem(item, originalIndex)}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
};

export default WaterfallLayout;

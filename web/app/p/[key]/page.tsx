"use client";

import { Alert, Box, Skeleton, Stack } from "@mui/material";
import { Dropdown } from "@/components/Dropdown";
import BoardHeader from "./components/BoardHeader";
import FilterChips from "./components/FilterChips";
import BoardColumn from "./components/BoardColumn";
import SprintPickDialog from "./components/SprintPickDialog";
import LaunchDialog from "./components/LaunchDialog";
import { useParams } from "next/navigation";
import { useEffect } from "react";
import { useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import {
  sprintAction,
  createSprint,
  level1Type,
  syncCurrentParent,
} from "@/lib/sprints";
import useBoardData from "./hooks/useBoardData";
import PullDialog from "./components/PullDialog";
import CloseReasonDialog from "@/components/CloseReasonDialog";
import { shouldAsk, type CloseReason } from "@/lib/closeReason";
import type { Filter, Item, SprintLite, Status } from "./types";

export default function BoardPage() {
  const { key } = useParams<{ key: string }>();
  const {
    statuses,
    items,
    setItems,
    activeSprints,
    sprints,
    estScales,
    estRequired,
    loading,
    error,
    setError,
    load,
  } = useBoardData(key);
  const [newTitle, setNewTitle] = useState("");
  const [newEstValue, setNewEstValue] = useState(""); // YPJ-186 预估表单态(成对空串=未填)
  const [newEstUnit, setNewEstUnit] = useState("");
  // YPJ-255(spec 0159 拍板):缺 Estimate 占位——快创建预选「中」档(key=m,自定义刻度无 m 取排序中位);
  // deps 仅 estScales:用户清空后不回弹
  useEffect(() => {
    if (estScales.length === 0) return;
    const mid =
      estScales.find((s) => s.key === "m") ??
      [...estScales].sort((a, b) => a.value - b.value)[
        Math.floor(estScales.length / 2)
      ];
    setNewEstValue(String(mid.value));
    setNewEstUnit(mid.unit);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [estScales]);
  const [pendingEst, setPendingEst] = useState({ value: "", unit: "" }); // S2/S3 对话框路径的快照(同 pendingTitle)
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [sprintInfo, setSprintInfo] = useState(""); // YPJ-120:开始动作自动入 sprint 的轻提示
  const [filter, setFilter] = useState<Filter>("all");
  // YPJ-257(spec 0168):filter 落 localStorage,详情页桶内导航同谓词过滤;SSR 安全,挂载后回读
  useEffect(() => {
    const saved = localStorage.getItem(
      `yz.board.filter.${key}`,
    ) as Filter | null;
    if (saved) setFilter(saved);
  }, [key]);
  const changeFilter: (f: Filter) => void = (f) => {
    setFilter(f);
    if (f === "all") localStorage.removeItem(`yz.board.filter.${key}`);
    else localStorage.setItem(`yz.board.filter.${key}`, f);
  };
  const [sprintPickOpen, setSprintPickOpen] = useState(false);
  const [pendingTitle, setPendingTitle] = useState("");
  const [pullOpen, setPullOpen] = useState(false);
  // 0105:S2/S2m(activate)/S3(create)前置确认弹窗;取消=放弃创建(D6)
  const [launchOpen, setLaunchOpen] = useState(false);
  const [launchMode, setLaunchMode] = useState<"activate" | "create">(
    "activate",
  );
  const [launchPick, setLaunchPick] = useState("");
  const [launchName, setLaunchName] = useState("");
  // YPJ-275:转终态弹窗(源状态 requireCloseReason=true);取消 = 卡片不动(不乐观更新)
  const [closeAsk, setCloseAsk] = useState<{
    itemId: string;
    itemNumber: string;
    status: Status;
  } | null>(null);
  const [closeError, setCloseError] = useState("");

  async function addItem(e: React.FormEvent) {
    e.preventDefault();
    if (!newTitle.trim() || loading) return; // loading 未就绪时 sprints 可能是空态,误判场景
    const title = newTitle.trim();
    const active = sprints.filter((s) => s.status === "active");
    const planned = sprints.filter((s) => s.status === "planned");
    if (active.length > 1) {
      // S1m:多进行中组 → 用户选(归属是用户意图,不代选)
      setPendingTitle(title);
      setPendingEst({ value: newEstValue, unit: newEstUnit });
      setSprintPickOpen(true);
      return;
    }
    if (active.length === 1) {
      // S1:直挂该组
      await createInGroup(title, active[0].sprintId, {
        value: newEstValue,
        unit: newEstUnit,
      });
      return;
    }
    if (planned.length > 0) {
      // S2/S2m:激活并放入(确认制;取消=放弃创建,D6)
      setPendingTitle(title);
      setPendingEst({ value: newEstValue, unit: newEstUnit });
      setLaunchPick(planned[0].sprintId);
      setLaunchMode("activate");
      setLaunchOpen(true);
      return;
    }
    // 0 进行中 0 计划中:S0(无 level-1 类型)或 S3(有类型无非完成态实例)——查类型区分
    try {
      const l1 = await level1Type(key);
      if (!l1) {
        // S0:无组概念,onBoard 上看板(0101 铁律);请求失败会 throw → 不静默当 S0
        await createFree(title, { value: newEstValue, unit: newEstUnit });
        return;
      }
      // S3:新建组并激活;默认名=类型名+实例数+1,撞名递增
      let n = sprints.length + 1;
      while (sprints.some((s) => s.name === `${l1.name} ${n}`)) n += 1;
      setPendingTitle(title);
      setPendingEst({ value: newEstValue, unit: newEstUnit });
      setLaunchMode("create");
      setLaunchName(`${l1.name} ${n}`);
      setLaunchOpen(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  /** S1/S1m/S2/S3 共用:建 item 并挂入组(后端 create 同事务校验 level-1 ∧ 非 completed)。 */
  async function createInGroup(
    title: string,
    groupId: string,
    est?: { value: string; unit: string },
  ) {
    try {
      await api(`/api/projects/${key}/items`, {
        method: "POST",
        body: JSON.stringify({
          title,
          sprintId: groupId,
          estimateValue: est?.value || null,
          estimateUnit: est?.unit || null,
        }),
      });
      setNewTitle("");
      setNewEstValue("");
      setNewEstUnit("");
      setSprintPickOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  /** 仅 S0(无组概念):建 item + onBoard 上看板(0099;无 active 时不挂组)。 */
  async function createFree(
    title: string,
    est?: { value: string; unit: string },
  ) {
    try {
      const created = await api<{ itemId: string }>(
        `/api/projects/${key}/items`,
        {
          method: "POST",
          body: JSON.stringify({
            title,
            estimateValue: est?.value || null,
            estimateUnit: est?.unit || null,
          }),
        },
      );
      await api(`/api/items/${created.itemId}/board-membership`, {
        method: "PUT",
        body: JSON.stringify({ onBoard: true }),
      });
      setNewTitle("");
      setNewEstValue("");
      setNewEstUnit("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  /** 0105:确认=S2 激活并放入 / S3 新建组+激活+放入(组先行,item 后行;激活失败降级为挂入计划中组)。 */
  async function confirmLaunch() {
    const title = pendingTitle;
    const est = pendingEst;
    setLaunchOpen(false);
    if (launchMode === "activate") {
      if (!launchPick) return;
      try {
        await sprintAction(key, launchPick, "start");
      } catch (err) {
        setError(err instanceof Error ? err.message : t.board.startFailed);
      }
      await createInGroup(title, launchPick, est);
      return;
    }
    const name = launchName.trim();
    if (!name) return;
    try {
      // 防重:同名组复用不重建(激活失败重试场景)
      const existing = sprints.find((s) => s.name === name);
      const gid = existing
        ? existing.sprintId
        : (
            await createSprint(key, {
              name,
              startDate: null,
              endDate: null,
              // spec 0112:新建组落「当前 epic」(active 子组的父 → localStorage 记忆 → 无)
              parentGroupId: syncCurrentParent(key, sprints),
            })
          ).sprintId;
      try {
        await sprintAction(key, gid, "start");
      } catch (err) {
        setError(err instanceof Error ? err.message : t.board.startFailed);
        // 降级:新组已建但未激活,组列表已变化 → 手工补广播(sprintAction 失败不广播)
        window.dispatchEvent(new Event("sprints-changed"));
      }
      await createInGroup(title, gid, est);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  // YPJ-29:看板内一键激活 planned sprint(激活后重拉 board,范围收窄)
  async function startSprint(sprintId: string) {
    if (!sprintId) return;
    try {
      await sprintAction(key, sprintId, "start");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.startFailed);
    }
  }

  async function moveTo(itemId: string, status: Status) {
    const item = items.find((i) => i.itemId === itemId);
    if (!item || item.status === status.name) return;
    if (shouldAsk(statuses, item.status, status)) {
      setCloseError("");
      setCloseAsk({ itemId, itemNumber: item.number, status });
      return;
    }
    try {
      setItems((prev) =>
        prev.map((i) =>
          i.itemId === itemId ? { ...i, status: status.name } : i,
        ),
      );
      const resp = await api<{ autoAssignedSprintName?: string | null }>(
        `/api/items/${itemId}`,
        {
          method: "PATCH",
          body: JSON.stringify({ statusItemId: status.statusId }),
        },
      );
      await load();
      if (resp.autoAssignedSprintName) {
        setSprintInfo(
          `已自动加入 sprint「${resp.autoAssignedSprintName}」;不想进可手动移出`,
        );
        setTimeout(() => setSprintInfo(""), 6000);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.moveFailed);
      await load();
    }
  }

  /** YPJ-275:弹窗确认 = PATCH 带理由迁移;失败原地报错不关窗 */
  async function confirmClose(reason: CloseReason, note: string | null) {
    if (!closeAsk) return;
    try {
      await api(`/api/items/${closeAsk.itemId}`, {
        method: "PATCH",
        body: JSON.stringify({
          statusItemId: closeAsk.status.statusId,
          closeReason: reason,
          closeNote: reason === "done" ? null : note,
        }),
      });
      setCloseAsk(null);
      await load();
    } catch (err) {
      setCloseError(err instanceof Error ? err.message : t.board.moveFailed);
    }
  }

  // YPJ-238:列内排序不再拖拽(用户拍板:箭头排序);按当前列显示序相邻交换后 PUT 全列。用全量 items(过滤隐藏卡不能被当僵尸删行)
  const byBoardOrder = (a: Item, b: Item) =>
    (a.boardOrder ?? Number.MAX_SAFE_INTEGER) -
      (b.boardOrder ?? Number.MAX_SAFE_INTEGER) ||
    a.number.localeCompare(b.number, undefined, { numeric: true });
  const sortCol = (list: Item[]) => [...list].sort(byBoardOrder);
  const moveByArrow = async (itemId: string, status: Status, dir: -1 | 1) => {
    try {
      // YPJ-238(spec §3.1):move 端点只动新表至多 2 行;响应 ordered 就地 patch,零全量刷新(prod 性能拍板)
      const resp = await api<{
        status: string;
        ordered: { itemId: string; boardOrder: number | null }[];
      }>(`/api/projects/${key}/board-order/move`, {
        method: "POST",
        body: JSON.stringify({
          itemId,
          dir: dir === -1 ? "up" : "down",
        }),
      });
      const byId = new Map(resp.ordered.map((o) => [o.itemId, o.boardOrder]));
      setItems((prev) =>
        prev.map((i) =>
          byId.has(i.itemId)
            ? { ...i, boardOrder: byId.get(i.itemId) ?? null }
            : i,
        ),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.moveFailed);
      await load();
    }
  };

  // YPJ-238:排序模式 = 正在调序的列名(null = 关闭)
  const [sortingCol, setSortingCol] = useState<string | null>(null);
  // S7:assignee filter (purely frontend; "unassigned" = assignee is null); sprint scope is determined by the backend board endpoint (YPJ-29)
  const filtered =
    filter === "all"
      ? items
      : filter === "unassigned"
        ? items.filter((i) => !i.assignee)
        : filter === "blocked"
          ? items.filter((i) => i.blocked)
          : items.filter((i) => i.assignee === filter);
  const memberOptions = [
    ...new Set(items.map((i) => i.assignee).filter((a): a is string => !!a)),
  ];

  const finalNames = new Set(
    statuses.filter((s) => s.statusType === "FINAL").map((s) => s.name),
  );
  const byStatusName = new Map<string, Item[]>();
  statuses.forEach((s) => byStatusName.set(s.name, []));
  filtered.forEach((it) => {
    const list = byStatusName.get(it.status) ?? [];
    list.push(it);
    byStatusName.set(it.status, list);
  });
  // YPJ-238(spec 0166):列内排序键 boardOrder ASC → number 兜底(沉底;自然序保持后端口径)
  // YPJ-238 修复:sortCol 返回新数组,必须写回 map;之前返回值被丢弃导致列显示序永不变(箭头看起来"没反应")
  byStatusName.forEach((list, name) => byStatusName.set(name, sortCol(list)));

  return (
    <Box sx={{ p: 3 }}>
      <BoardHeader
        projectKey={key}
        loading={loading}
        estRequired={estRequired}
        estScales={estScales}
        newTitle={newTitle}
        onTitleChange={setNewTitle}
        estValue={newEstValue}
        estUnit={newEstUnit}
        onEstChange={(v, u) => {
          setNewEstValue(v);
          setNewEstUnit(u);
        }}
        onAddItem={addItem}
        onRefresh={() => load()}
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {sprintInfo && (
        <Alert
          severity="success"
          sx={{ mb: 2 }}
          onClose={() => setSprintInfo("")}
        >
          {sprintInfo}
        </Alert>
      )}

      {/* YPJ-29:无 active sprint → 提示条 + 「开始 sprint」(激活后看板自动收窄) */}
      {!loading && activeSprints.length === 0 && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
          <Alert severity="info" sx={{ py: 0.5, flex: 1 }}>
            {sprints.length === 0 ? t.board.noGroups : t.board.noActiveSprint}
          </Alert>
          {sprints.some((s) => s.status === "planned") && (
            <Dropdown
              ariaLabel={t.board.startSprint}
              size="small"
              value=""
              onChange={(v) => startSprint(v)}
              sx={{ width: 240 }}
              options={sprints
                .filter((s) => s.status === "planned")
                .map((s) => ({
                  value: s.sprintId,
                  label: `${t.board.startSprint}: ${s.name}`,
                }))}
            />
          )}
        </Stack>
      )}

      <FilterChips
        filter={filter}
        onFilterChange={changeFilter}
        memberOptions={memberOptions}
        onOpenPull={() => setPullOpen(true)}
      />

      {loading ? (
        <Stack direction="row" spacing={2}>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} variant="rounded" width={280} height={400} />
          ))}
        </Stack>
      ) : (
        <Stack
          direction="row"
          spacing={2}
          sx={{ overflowX: "auto", alignItems: "flex-start", pb: 2 }}
        >
          {statuses.map((s) => {
            const list = byStatusName.get(s.name) ?? [];
            return (
              <BoardColumn
                key={s.statusId}
                status={s}
                list={list}
                dragOver={dragOver}
                onDragOver={(e) => {
                  e.preventDefault();
                  e.dataTransfer.dropEffect = "move";
                  setDragOver(s.statusId);
                }}
                onDragLeave={() =>
                  setDragOver((cur) => (cur === s.statusId ? null : cur))
                }
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(null);
                  // YPJ-238(修订):拖拽仅跨列迁移;列内排序走排序按钮+箭头
                  const itemId = e.dataTransfer.getData("text/plain");
                  if (itemId) moveTo(itemId, s);
                }}
                projectKey={key}
                onRemoved={load}
                onAssigneeChanged={load}
                sorting={sortingCol === s.name}
                onToggleSort={() =>
                  setSortingCol(sortingCol === s.name ? null : s.name)
                }
                onMoveArrow={(itemId, dir) => void moveByArrow(itemId, s, dir)}
              />
            );
          })}
        </Stack>
      )}
      <SprintPickDialog
        open={sprintPickOpen}
        onClose={() => setSprintPickOpen(false)}
        activeSprints={activeSprints}
        onPick={(sprintId) => createInGroup(pendingTitle, sprintId, pendingEst)}
      />
      {/* 0105 S2/S2m/S3:创建前置确认——激活并放入/新建并激活;取消=放弃创建(D6) */}
      <LaunchDialog
        open={launchOpen}
        onClose={() => setLaunchOpen(false)}
        mode={launchMode}
        sprints={sprints}
        pick={launchPick}
        onPick={setLaunchPick}
        name={launchName}
        onNameChange={setLaunchName}
        onConfirm={confirmLaunch}
      />
      <PullDialog
        open={pullOpen}
        projectKey={key}
        onClose={() => setPullOpen(false)}
        onPulled={load}
      />
      <CloseReasonDialog
        open={closeAsk !== null}
        title={
          closeAsk
            ? t.closeReason.closeTitle(closeAsk.itemNumber)
            : t.closeReason.label
        }
        error={closeError}
        onCancel={() => setCloseAsk(null)}
        onConfirm={confirmClose}
      />
    </Box>
  );
}

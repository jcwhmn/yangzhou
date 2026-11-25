"use client";

import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Snackbar,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";

import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";

import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";

import { PageShell } from "@/components/PageShell";

import { Dropdown } from "@/components/Dropdown";

import Link from "next/link";

import { useParams, useRouter } from "next/navigation";

import { useCallback, useEffect, useState } from "react";

import { api } from "@/lib/api";

import { resolveBack } from "@/lib/back";

import { t } from "@/lib/texts";
import { listSprints, sprintAction, assignSprint } from "@/lib/sprints";

import { SignalChip } from "@/components/Verdict";

import { AssignPopover } from "@/components/AssignPopover";

import { DateInput } from "@/components/DateInput";
import CloseReasonDialog from "@/components/CloseReasonDialog";
import { shouldAsk, type CloseReason } from "@/lib/closeReason";
import { EstimateField } from "@/components/EstimateField";
import { EstimateScale } from "@/lib/estimate";

import type { Filter } from "../../types";

import {
  type Status,
  type ItemTypeLite,
  type Item,
  type SprintLite,
  type MilestoneLite,
  type Attribute,
  type Feasibility,
  type Activity,
  type ReqRow,
  type AttrRow,
  type SearchHit,
  UUID_RE,
} from "./types";

import { RequirementsDialog } from "./components/RequirementsDialog";
import { CommentsTab } from "./components/CommentsTab";
import { ActivityTab } from "./components/ActivityTab";
import { FeasibilityTab } from "./components/FeasibilityTab";
import { RequirementsTab } from "./components/RequirementsTab";
import { DependenciesTab } from "./components/DependenciesTab";
import { TimeLogTab } from "./components/TimeLogTab";
import { AttributesTab } from "./components/AttributesTab";
import { GithubTab } from "./components/GithubTab";
import { SubitemsBlock } from "./components/SubitemsBlock";

import { useItemNav } from "./hooks/useItemNav";

import { useItemComments } from "./hooks/useItemComments";

import { useItemTime } from "./hooks/useItemTime";

import { useItemDependencies } from "./hooks/useItemDependencies";

import { useItemChecklist } from "./hooks/useItemChecklist";

import { useSubitems } from "./hooks/useSubitems";

import { useItemRelease } from "./hooks/useItemRelease";

export default function ItemDetailPage() {
  const { key, itemId: paramId } = useParams<{ key: string; itemId: string }>();

  const router = useRouter();

  const [resolvedId, setResolvedId] = useState<string | null>(() =>
    UUID_RE.test(paramId) ? paramId : null,
  );

  const [numNotFound, setNumNotFound] = useState(false);

  const itemId = resolvedId ?? "";

  useEffect(() => {
    if (resolvedId) return;

    let dead = false;

    api<SearchHit[]>(`/api/search?q=${encodeURIComponent(paramId)}`)
      .then((hits) => {
        if (dead) return;

        const hit = hits.find(
          (h) => h.number.toUpperCase() === paramId.toUpperCase(),
        );

        if (!hit) {
          setNumNotFound(true);

          return;
        }

        if (hit.projectKey !== key) {
          router.replace(`/p/${hit.projectKey}/i/${hit.number}`); // key 段与编号不符,纠正 URL

          return;
        }

        setResolvedId(hit.itemId);
      })

      .catch(() => {
        if (!dead) setNumNotFound(true);
      });

    return () => {
      dead = true;
    };
  }, [paramId, key, resolvedId, router]);

  const [item, setItem] = useState<Item | null>(null);

  const [statuses, setStatuses] = useState<Status[]>([]);

  const [attributes, setAttributes] = useState<Attribute[]>([]);

  const [feasibility, setFeasibility] = useState<Feasibility | null>(null);

  const [activity, setActivity] = useState<Activity[]>([]);

  const [title, setTitle] = useState("");

  const [description, setDescription] = useState("");

  const [startDate, setStartDate] = useState("");

  const [startTime, setStartTime] = useState(""); // YPJ-208 切片 c:HH:mm,与 startDate 拼 T

  const [dueDate, setDueDate] = useState("");

  const [dueTime, setDueTime] = useState(""); // YPJ-208 切片 c

  const [priority, setPriority] = useState("");

  const [estValue, setEstValue] = useState(""); // YPJ-186 预估表单态(成对空串=未填)

  const [estUnit, setEstUnit] = useState("");

  const [estScales, setEstScales] = useState<EstimateScale[]>([]);

  const [estRequired, setEstRequired] = useState(false);

  // 自定义值非法(单位有值但数值空/≤0)→ 禁保存(后端成对校验兕底)
  const estInvalid =
    estUnit !== "" && (estValue === "" || Number(estValue) <= 0);

  const [type, setType] = useState("task");

  const [typeOptions, setTypeOptions] = useState<ItemTypeLite[]>([]);

  const [sprints, setSprints] = useState<SprintLite[]>([]);

  const [milestones, setMilestones] = useState<MilestoneLite[]>([]);

  const [saved, setSaved] = useState(false);

  // YPJ-275:关闭理由弹窗——mode move=转终态(带目标状态) / edit=终态票改理由
  const [closeAsk, setCloseAsk] = useState<{
    mode: "move" | "edit";
    target?: Status;
  } | null>(null);
  const [closeError, setCloseError] = useState("");

  const [sprintHint, setSprintHint] = useState(""); // YPJ-120:开始动作自动入 sprint 的轻提示
  const [launchAskId, setLaunchAskId] = useState<string | null>(null); // YPJ-104:挂 planned 后提示激活(spec 0105)

  const [error, setError] = useState("");

  // YPJ-244(spec 0151)切片2:due/estimate 后写者为准——本编辑会话 dirty 跟踪,保存时后改字段后发 PATCH
  const [dueDirty, setDueDirty] = useState(false);
  const [estDirty, setEstDirty] = useState(false);
  const [lastEdited, setLastEdited] = useState<"due" | "est" | null>(null);
  const [warnMsg, setWarnMsg] = useState<string | null>(null); // PATCH 响应 warnings toast(0150 通道消费)

  const [memberNames, setMemberNames] = useState<Map<string, string>>(
    new Map(),
  );

  const [reqOpen, setReqOpen] = useState(false);

  const [tab, setTab] = useState(0);

  const [back, setBack] = useState(() => resolveBack(null, key));

  // ?from= 白名单 token → 返回来源页(YPJ-31);不用 useSearchParams,免 Suspense 边界要求

  useEffect(() => {
    const from = new URLSearchParams(window.location.search).get("from");

    setBack(resolveBack(from, key));
  }, [key]);

  const load = useCallback(async () => {
    if (!itemId) return;

    const [it, project, attrs, feas, acts, spr, msLit, tps] = await Promise.all(
      [
        api<Item>(`/api/items/${itemId}`),

        api<{
          statuses: Status[];
          estimateScales: EstimateScale[];
          estimateRequired: boolean;
        }>(`/api/projects/${key}`),

        api<Attribute[]>("/api/attributes"),

        api<Feasibility>(`/api/items/${itemId}/feasibility`),

        api<Activity[]>(`/api/items/${itemId}/activity`),

        // sprints 并入首屏:选项一次性到位,避免下拉打开后 children 变化把 MUI 菜单关掉

        listSprints(key)
          .catch(
            () =>
              new Promise<SprintLite[]>((res) =>
                setTimeout(() => res(listSprints(key)), 1000),
              ),
          ) // CI 冷 JVM 偶发连接重置(status -1),延迟重试,否则静默成空选项

          .catch(() => [] as SprintLite[]),

        api<MilestoneLite[]>(`/api/projects/${key}/milestones`).catch(
          () => [] as MilestoneLite[],
        ),

        api<ItemTypeLite[]>(`/api/projects/${key}/item-types`).catch(
          () => [] as ItemTypeLite[],
        ),
      ],
    );

    setMilestones(msLit);

    setTypeOptions(tps);

    setItem(it);
    setTitle(it.title);
    setDescription(it.description ?? "");
    setType(it.type);

    setStartDate(it.startDate?.slice(0, 10) ?? "");
    setStartTime(it.startDate?.slice(11, 16) ?? "");
    setDueDate(it.dueDate?.slice(0, 10) ?? "");
    setDueTime(it.dueDate?.slice(11, 16) ?? "");
    setPriority(it.priority ?? "");
    setEstValue(it.estimateValue != null ? String(it.estimateValue) : "");
    setEstUnit(it.estimateUnit ?? "");
    setDueDirty(false);
    setEstDirty(false);
    setLastEdited(null);

    setStatuses(project.statuses);
    setEstScales(project.estimateScales ?? []);
    setEstRequired(project.estimateRequired ?? false);
    setAttributes(attrs);
    setSprints(spr);

    setFeasibility(feas);
    setActivity(acts);
  }, [key, itemId]);

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, [load]);

  // ---- 领域 hooks(YPJ-257 页面重构:评论/工时/依赖/清单/子条目/release/导航)----
  const { comments, newComment, setNewComment, addComment, removeComment } =
    useItemComments(itemId);

  const { releases, releaseId, setReleaseMembership } = useItemRelease(
    key,
    itemId,
    setError,
  );

  const {
    timeLog,
    manualMinutes,
    setManualMinutes,
    manualNote,
    setManualNote,
    suggestions,
    suggestionsBusy,
    startTimer,
    stopTimer,
    addManual,
    loadSuggestions,
    logSuggestion,
  } = useItemTime(itemId, setError, load);

  const {
    deps,
    depBlocked,
    depAddOpen,
    setDepAddOpen,
    addDependency,
    removeDep,
  } = useItemDependencies(itemId, setError);

  const {
    checklist,
    newCheckText,
    setNewCheckText,
    addCheckEntry,
    toggleCheckEntry,
    removeCheckEntry,
  } = useItemChecklist(itemId, setError);

  const {
    subitems,
    newSubTitle,
    setNewSubTitle,
    subPolicyDialog,
    setSubPolicyDialog,
    timelineLocked,
    addSubitem,
    addSubitemWithPolicy,
  } = useSubitems(
    itemId,
    key,
    item?.title ?? "父 item",
    { startDate, dueDate },
    () => {
      setStartDate("");
      setStartTime("");
      setDueDate("");
      setDueTime("");
    },
    setError,
    load,
  );

  const { nav, navEdge, setNavEdge } = useItemNav(
    item,
    itemId,
    key,
    sprints,
    statuses,
  );

  const flashSaved = () => {
    setSaved(true);

    setTimeout(() => setSaved(false), 1500);
  };

  async function saveBasics() {
    if (estInvalid) return;
    // YPJ-244(spec 0151):due 与 estimate 都被改时,后改字段后发 PATCH(API 后写者为准;
    // 单改/未改维持单 PATCH——同值重发不触发互算,0150 通用门)。start 恒随第一个 PATCH。
    const base = {
      title,
      description: description || null,
      type,
      startDate: startDate
        ? startTime
          ? `${startDate}T${startTime}`
          : startDate
        : "",
      priority,
    };
    const duePart = {
      dueDate: dueDate ? (dueTime ? `${dueDate}T${dueTime}` : dueDate) : "",
    };
    const estPart = { estimateValue: estValue, estimateUnit: estUnit };
    const patch = (body: object) =>
      api<{ warnings?: string[] }>(`/api/items/${itemId}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      });
    const collect = (rs: { warnings?: string[] }[]) => {
      const msgs = rs.flatMap((r) => r.warnings ?? []);
      if (msgs.length > 0) setWarnMsg(msgs.join("\n"));
    };
    try {
      const rs: { warnings?: string[] }[] = [];
      if (dueDirty && estDirty && lastEdited === "est") {
        rs.push(await patch({ ...base, ...duePart }));
        rs.push(await patch(estPart));
      } else if (dueDirty && estDirty) {
        rs.push(await patch({ ...base, ...estPart }));
        rs.push(await patch(duePart));
      } else {
        rs.push(await patch({ ...base, ...duePart, ...estPart }));
      }

      collect(rs);

      await load();

      flashSaved();
    } catch (e) {
      // V13-S4:409 等业务错误浮出(如「开工前请先指派负责人」)

      setError(e instanceof Error ? e.message : "保存失败");
    }
  }

  async function moveStatus(statusName: string) {
    const target = statuses.find((s) => s.name === statusName);

    if (!target) return;

    // YPJ-275:源状态要求理由 ∧ 目标终态 → 弹窗,不直接迁移
    if (item && shouldAsk(statuses, item.status, target)) {
      setCloseError("");
      setCloseAsk({ mode: "move", target });
      return;
    }

    try {
      const resp = await api<{ autoAssignedSprintName?: string | null }>(
        `/api/items/${itemId}`,
        {
          method: "PATCH",
          body: JSON.stringify({ statusItemId: target.statusId }),
        },
      );

      await load();

      if (resp.autoAssignedSprintName) {
        setSprintHint(
          `已自动加入 sprint「${resp.autoAssignedSprintName}」;不想进可手动移出`,
        );

        setTimeout(() => setSprintHint(""), 6000);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    }
  }

  /** YPJ-275:弹窗确认——move=带理由迁移终态;edit=终态票只改理由;失败原地报错不关窗 */
  async function confirmClose(reason: CloseReason, note: string | null) {
    if (!closeAsk || !item) return;
    const body =
      closeAsk.mode === "move"
        ? {
            statusItemId: closeAsk.target!.statusId,
            closeReason: reason,
            closeNote: reason === "done" ? null : note,
          }
        : { closeReason: reason, closeNote: reason === "done" ? null : note };
    try {
      await api(`/api/items/${itemId}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      });
      setCloseAsk(null);
      await load();
    } catch (e) {
      setCloseError(e instanceof Error ? e.message : "保存失败");
    }
  }

  async function assign(memberId: string | null) {
    await api(`/api/items/${itemId}/assignee`, {
      method: "PUT",
      body: JSON.stringify({ assigneeItemId: memberId }),
    });

    await load();
  }

  // 票 2(US-M7):挂/摘 gate——切该站的依据全量(PUT),回刷详情
  async function toggleMilestone(msId: string) {
    try {
      const gates = await api<{ itemId: string }[]>(
        `/api/projects/${key}/milestones/${msId}/items`,
      );
      const ids = gates.map((g) => g.itemId);
      const next = ids.includes(itemId)
        ? ids.filter((id) => id !== itemId)
        : [...ids, itemId];
      await api(`/api/projects/${key}/milestones/${msId}/items`, {
        method: "PUT",
        body: JSON.stringify({ itemIds: next }),
      });
      setItem(await api<Item>(`/api/items/${itemId}`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "挂接失败");
    }
  }

  // V12-S3:指派/移出当前 sprint(值 = 指派,null = 移出;completed sprint 不在选项中)

  async function applySprint(sprintId: string | null) {
    try {
      await assignSprint(key, itemId, sprintId);
      const it = await api<Item>(`/api/items/${itemId}`);

      setItem(it);
      // YPJ-104:挂 planned 且项目无活动 sprint → 提示激活(确认后 item 随组可见,spec 0105)
      const target = sprints.find((s) => s.sprintId === sprintId);
      if (
        target?.status === "planned" &&
        !sprints.some((s) => s.status === "active")
      ) {
        setLaunchAskId(sprintId);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "指派失败");
    }
  }

  // YPJ-104:确认激活——sprint 变 active,item 随组立即可见于看板(零迁移)
  async function confirmLaunchAsk() {
    const id = launchAskId;
    setLaunchAskId(null);
    if (!id) return;
    try {
      await sprintAction(key, id, "start");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.board.startFailed);
    }
  }

  async function saveRequirements(rows: ReqRow[]) {
    await api(`/api/items/${itemId}/requirements`, {
      method: "PUT",

      body: JSON.stringify({ requirements: rows.filter((r) => r.attribute) }),
    });

    await load();

    flashSaved();
  }

  async function saveAttributes(rows: AttrRow[]) {
    await api(`/api/items/${itemId}/attributes`, {
      method: "PUT",

      body: JSON.stringify({ values: rows }),
    });

    await load();

    flashSaved();
  }

  if (!item) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color={error ? "error" : "text.secondary"}>
          {numNotFound ? t.item.number_not_found : error || "加载中…"}
        </Typography>
      </Box>
    );
  }

  return (
    <PageShell>
      <Link href={back.href} style={{ textDecoration: "none" }}>
        <Typography variant="body2" color="primary" gutterBottom>
          {back.label}
        </Typography>
      </Link>

      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        {item.parentNumber && (
          <Link
            href={`/p/${key}/i/${item.parentItemId}`}
            style={{ textDecoration: "none" }}
          >
            <Typography
              variant="body2"
              color="primary"
              sx={{ whiteSpace: "nowrap" }}
            >
              {item.parentNumber} ›
            </Typography>
          </Link>
        )}

        <Chip label={item.number} />

        {feasibility && <SignalChip signal={feasibility.signal} />}

        {item.assignee ? (
          <AssignPopover
            itemId={itemId}
            assignee={item.assignee}
            onChanged={load}
          >
            {(onClick) => (
              <Chip
                size="small"
                label={`👤 ${item.assignee}`}
                onClick={onClick}
                onDelete={() => assign(null)}
              />
            )}
          </AssignPopover>
        ) : (
          <AssignPopover itemId={itemId} assignee={null} onChanged={load}>
            {(onClick) => (
              <Chip
                size="small"

                variant="outlined"

                label={t.assign.open}

                onClick={onClick}

                sx={{ opacity: 0.55 }}
              />
            )}
          </AssignPopover>
        )}

        <Dropdown
          ariaLabel="item 状态"

          size="small"

          value={item.status}

          onChange={(v) => moveStatus(v)}

          sx={{ minWidth: 160 }}

          options={statuses.map((s) => ({ value: s.name, label: s.name }))}
        />

        {nav && nav.total > 1 && (
          <Stack direction="row" spacing={0.5} alignItems="center">
            {nav.prev && (
              <Tooltip title={t.item.prevTip}>
                <IconButton
                  size="small"
                  aria-label="previous item"
                  href={`/p/${key}/i/${nav.prev}`}
                >
                  <ChevronLeftOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ whiteSpace: "nowrap" }}
            >
              {nav.index + 1} / {nav.total}
            </Typography>
            {nav.next && (
              <Tooltip title={t.item.nextTip}>
                <IconButton
                  size="small"
                  aria-label="next item"
                  href={`/p/${key}/i/${nav.next}`}
                >
                  <ChevronRightOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
          </Stack>
        )}

        {statuses.find((s) => s.name === item.status)?.statusType ===
          "FINAL" && (
          <Tooltip title={t.closeReason.editTitle}>
            <Chip
              size="small"
              onClick={() => {
                setCloseError("");
                setCloseAsk({ mode: "edit" });
              }}
              color={item.closeReason === "done" ? "default" : "warning"}
              label={
                (item.closeReason &&
                  t.closeReason[item.closeReason as CloseReason]) ??
                item.closeReason
              }
            />
          </Tooltip>
        )}
      </Stack>

      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}

      <Box sx={{ display: "flex", gap: 4, alignItems: "flex-start" }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Stack spacing={3} component="section">
            <TextField
              label={t.item.title}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              fullWidth
            />

            <TextField
              label={t.item.description}

              value={description}

              onChange={(e) => setDescription(e.target.value)}

              multiline

              minRows={2}

              fullWidth
            />

            <Stack direction="row" spacing={1} alignItems="flex-start">
              {/* YPJ-218 spec 0139:已有子的父不持有自有时间线——四框禁用,保存原样回传 */}
              <DateInput
                label="开始日期"
                size="small"
                value={startDate}
                onChange={setStartDate}
                sx={{ width: 200 }}
                disabled={timelineLocked}
              />

              {/* YPJ-208 切片 c:时刻录入(datetime 语义;空=date-only 由后端按日历默认) */}
              <TextField
                label="开始时刻"
                size="small"
                type="time"
                value={startTime}
                onChange={(e) => setStartTime(e.target.value)}
                sx={{ width: 130 }}
                slotProps={{ inputLabel: { shrink: true } }}
                disabled={timelineLocked}
              />

              <DateInput
                label="截止日期"
                size="small"
                value={dueDate}
                onChange={(v) => {
                  setDueDate(v);
                  setDueDirty(true);
                  setLastEdited("due");
                }}
                sx={{ width: 200 }}
                disabled={timelineLocked}
              />

              <TextField
                label="截止时刻"
                size="small"
                type="time"
                value={dueTime}
                onChange={(e) => {
                  setDueTime(e.target.value);
                  setDueDirty(true);
                  setLastEdited("due");
                }}
                sx={{ width: 130 }}
                slotProps={{ inputLabel: { shrink: true } }}
                disabled={timelineLocked}
              />

              {timelineLocked && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ alignSelf: "center" }}
                >
                  已有子项,时间线由子项汇总
                </Typography>
              )}

              <Dropdown
                label={t.item.priority}

                size="small"

                value={priority}

                onChange={(v) => setPriority(v)}

                sx={{ width: 110 }}

                options={[
                  { value: "", label: "无" },
                  ...["P0", "P1", "P2", "P3"].map((p) => ({
                    value: p,
                    label: p,
                  })),
                ]}
              />

              {sprints.length > 0 && (
                <Dropdown
                  label={t.item.sprint}

                  size="small"

                  value={item.sprintId ?? ""}

                  onChange={(v) => applySprint(v || null)}

                  sx={{ width: 170 }}

                  clearable

                  options={sprints

                    .filter(
                      (s) =>
                        s.status !== "completed" ||
                        s.sprintId === item.sprintId,
                    )

                    .sort(
                      (a, b) =>
                        ["active", "planned", "completed"].indexOf(a.status) -
                        ["active", "planned", "completed"].indexOf(b.status),
                    )

                    .map((s) => ({
                      value: s.sprintId,

                      label: s.name,

                      disabled: s.status === "completed",

                      chip: {
                        label:
                          t.projectNav.sprintStatus[
                            s.status as keyof typeof t.projectNav.sprintStatus
                          ],

                        color:
                          s.status === "active"
                            ? "primary"
                            : s.status === "planned"
                              ? "warning"
                              : "default",
                      },
                    }))}
                />
              )}

              {item.sprintId && (
                <Link
                  href={`/p/${key}/sprint/${item.sprintId}`}
                  style={{ textDecoration: "none", alignSelf: "center" }}
                >
                  <Typography variant="caption" color="primary">
                    {item.sprintName}
                  </Typography>
                </Link>
              )}
            </Stack>

            {milestones.length > 0 && (
              <Stack
                direction="row"
                spacing={0.5}
                alignItems="center"
                flexWrap="wrap"
                useFlexGap
              >
                <Typography variant="caption" color="text.secondary">
                  {t.item.milestones}
                </Typography>
                {milestones.map((ms) => (
                  <Chip
                    key={ms.milestoneId}
                    data-testid="item-milestone-chip"
                    label={ms.name}
                    size="small"
                    color={
                      item?.milestoneIds?.includes(ms.milestoneId)
                        ? "primary"
                        : "default"
                    }
                    variant={
                      item?.milestoneIds?.includes(ms.milestoneId)
                        ? "filled"
                        : "outlined"
                    }
                    onClick={() => toggleMilestone(ms.milestoneId)}
                  />
                ))}
              </Stack>
            )}

            {/* YPJ-201:release 挂接——单选下拉;无归属显空串;released 不在选项 */}
            <Dropdown
              label={t.item.release}
              value={releaseId ?? ""}
              onChange={(v) => setReleaseMembership(v || null)}
              clearable={releaseId !== null}
              sx={{ width: 260 }}
              options={[
                { value: "", label: t.releases.stageNone },
                ...releases.map((r) => ({
                  value: r.releaseId,
                  label: r.name,
                })),
                // 已挂的 release 已不在 planned(已发布)→ 兜底回显,防选中态丢 label
                ...(releaseId &&
                !releases.some((r) => r.releaseId === releaseId)
                  ? [{ value: releaseId, label: t.releases.released }]
                  : []),
              ]}
            />

            <Dropdown
              label={t.item.type}

              value={type}

              onChange={(v) => setType(v)}

              sx={{ width: 200 }}

              options={
                // YPJ-177:动态定义(name 显示);历史值不在定义内时兜底回显
                [
                  ...typeOptions.map((tp) => ({
                    value: tp.typeKey,
                    label: tp.name,
                  })),
                  ...(type && !typeOptions.some((tp) => tp.typeKey === type)
                    ? [{ value: type, label: type }]
                    : []),
                ]
              }
            />

            <Stack
              direction="row"
              spacing={1}
              alignItems="center"
              flexWrap="wrap"
              useFlexGap
            >
              <EstimateField
                scales={estScales}
                required={estRequired}
                value={estValue}
                unit={estUnit}
                onChange={(v, u) => {
                  setEstValue(v);
                  setEstUnit(u);
                  setEstDirty(true);
                  setLastEdited("est");
                }}
              />
            </Stack>

            <Stack direction="row" spacing={1} alignItems="center">
              <Button
                variant="contained"
                onClick={saveBasics}
                disabled={estInvalid}
              >
                {t.item.save}
              </Button>

              {saved && (
                <Typography variant="body2" color="text.secondary">
                  {t.item.saved}
                </Typography>
              )}

              {sprintHint && (
                <Typography variant="body2" color="success.main">
                  {sprintHint}
                </Typography>
              )}

              <Button
                size="small"

                color="error"

                onClick={() => {
                  if (confirm(`删除 item ${item.number}?`)) {
                    api(`/api/items/${itemId}`, { method: "DELETE" })
                      .then(() => (window.location.href = back.href))

                      .catch((e) => setError(e.message));
                  }
                }}
              >
                删除
              </Button>
            </Stack>
          </Stack>

          {/* 子条目:主页面直排区块,置于页签栏上方(YPJ-84;核心功能不做页签,2026-10-24 用户拍板) */}

          <SubitemsBlock
            projectKey={String(key)}
            subitems={subitems}
            newSubTitle={newSubTitle}
            onSubTitleChange={setNewSubTitle}
            onAdd={addSubitem}
            policyOpen={subPolicyDialog}
            onPolicyClose={() => setSubPolicyDialog(false)}
            onPolicyIgnore={() => addSubitemWithPolicy("ignore")}
            onPolicyMigrate={() => addSubitemWithPolicy("migrate")}
          />

          <Tabs
            value={tab}
            onChange={(_, v) => setTab(v)}
            variant="scrollable"
            sx={{ mt: 3, mb: 2, minHeight: 32 }}
          >
            {t.item.detailTabs.map((label) => (
              <Tab key={label} label={label} sx={{ minHeight: 32, py: 0.5 }} />
            ))}
          </Tabs>

          {tab === 0 && (
            <CommentsTab
              comments={comments}
              newComment={newComment}
              onCommentChange={setNewComment}
              onAdd={addComment}
              onRemove={removeComment}
            />
          )}

          {tab === 1 && activity.length > 0 && (
            <ActivityTab activity={activity} memberNames={memberNames} />
          )}

          {tab === 2 && feasibility && (
            <FeasibilityTab
              feasibility={feasibility}
              onRequestEdit={() => setReqOpen(true)}
            />
          )}

          {/* 七区块横向页签:评论/日志/判定/需求/依赖+清单/工时/GitHub(子条目为主页面直排区块,不在页签内,YPJ-84) */}

          {tab === 3 && (
            <RequirementsTab
              requirements={item.requirements}
              onRequestEdit={() => setReqOpen(true)}
            />
          )}

          <RequirementsDialog
            open={reqOpen}

            onClose={() => setReqOpen(false)}

            onSave={saveRequirements}

            attributes={attributes}
          />

          {tab === 4 && (
            <DependenciesTab
              projectKey={String(key)}
              itemId={String(itemId)}
              deps={deps}
              depBlocked={depBlocked}
              checklist={checklist}
              newCheckText={newCheckText}
              onCheckTextChange={setNewCheckText}
              onAddCheck={addCheckEntry}
              onToggleCheck={toggleCheckEntry}
              onRemoveCheck={removeCheckEntry}
              onAddDep={addDependency}
              onRemoveDep={removeDep}
            />
          )}

          {tab === 5 && (
            <TimeLogTab
              timeLog={timeLog}
              manualMinutes={manualMinutes}
              manualNote={manualNote}
              onManualMinutesChange={setManualMinutes}
              onManualNoteChange={setManualNote}
              suggestions={suggestions}
              suggestionsBusy={suggestionsBusy}
              onStartTimer={startTimer}
              onStopTimer={stopTimer}
              onAddManual={addManual}
              onLoadSuggestions={loadSuggestions}
              onLogSuggestion={logSuggestion}
            />
          )}

          {tab === 7 && (
            <AttributesTab
              attributes={attributes}
              itemAttributes={item.attributes}
              onSaveAttrs={saveAttributes}
            />
          )}

          {tab === 6 && (
            <GithubTab
              projectKey={String(key)}
              itemId={String(itemId)}
              gitRefs={item.gitRefs}
              itemNumber={item.number}
              itemType={item.type}
              itemTitle={item.title}
              onCreated={load}
            />
          )}

          <Box sx={{ width: 240, flexShrink: 0 }}>
            <Typography variant="caption" color="text.secondary">
              创建
            </Typography>

            <Typography variant="body2" sx={{ mb: 2 }}>
              {item.createdAt
                ? new Date(item.createdAt).toLocaleString("zh-CN")
                : "—"}
            </Typography>

            <Stack spacing={1} alignItems="flex-start">
              {item.overdue && (
                <Chip size="small" color="error" label="已超期" />
              )}

              {item.dueSoon && !item.overdue && (
                <Chip size="small" color="warning" label="临期(3 天内)" />
              )}

              {item.blocked && (
                <Chip size="small" color="error" label="被阻塞" />
              )}
            </Stack>
          </Box>
        </Box>
      </Box>
      {/* YPJ-104:挂 planned 且无活动 sprint → 提示激活(spec 0105) */}
      <Dialog open={launchAskId !== null} onClose={() => setLaunchAskId(null)}>
        <DialogTitle>{t.item.launchAskTitle}</DialogTitle>
        <DialogContent>
          <Typography>
            {t.item.launchAsk.replace(
              "{name}",
              sprints.find((s) => s.sprintId === launchAskId)?.name ?? "",
            )}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setLaunchAskId(null)}>
            {t.item.launchLater}
          </Button>
          <Button variant="contained" onClick={confirmLaunchAsk}>
            {t.item.launchConfirm}
          </Button>
        </DialogActions>
      </Dialog>

      <CloseReasonDialog
        open={closeAsk !== null}
        title={
          closeAsk?.mode === "move" && item
            ? t.closeReason.closeTitle(item.number)
            : t.closeReason.editTitle
        }
        initialReason={(item?.closeReason as CloseReason) ?? "done"}
        initialNote={item?.closeNote ?? ""}
        error={closeError}
        confirmLabel={t.closeReason.save}
        onCancel={() => setCloseAsk(null)}
        onConfirm={confirmClose}
      />

      <Snackbar
        open={navEdge !== null}
        autoHideDuration={2000}
        onClose={() => setNavEdge(null)}
        anchorOrigin={{ vertical: "top", horizontal: "right" }}
      >
        <Alert severity="info" onClose={() => setNavEdge(null)}>
          {navEdge}
        </Alert>
      </Snackbar>

      <Snackbar
        open={warnMsg !== null}
        autoHideDuration={8000}
        onClose={() => setWarnMsg(null)}
        anchorOrigin={{ vertical: "top", horizontal: "right" }}
        data-testid="item-warnings"
      >
        <Alert severity="warning" onClose={() => setWarnMsg(null)}>
          <span style={{ whiteSpace: "pre-line" }}>{warnMsg}</span>
        </Alert>
      </Snackbar>
    </PageShell>
  );
}

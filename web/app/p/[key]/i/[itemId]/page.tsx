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
  type CommentDto,
  type Dep,
  type Checklist,
  type TimeEntry,
  type SuggestedEntry,
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

  // YPJ-201(US-R4,spec 0134):release 挂接——下拉只含 planned;released 历史不可达
  type ReleaseLite = {
    releaseId: string;
    name: string;
    status: string;
  };
  const [releases, setReleases] = useState<ReleaseLite[]>([]);
  const [releaseId, setReleaseId] = useState<string | null>(null);

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

  const [comments, setComments] = useState<CommentDto[]>([]);

  const [newComment, setNewComment] = useState("");

  const [timeLog, setTimeLog] = useState<{
    entries: TimeEntry[];
    totalMinutes: number;
  } | null>(null);

  const [manualMinutes, setManualMinutes] = useState("");

  const [manualNote, setManualNote] = useState("");

  const [suggestions, setSuggestions] = useState<SuggestedEntry[] | null>(null);

  const [suggestionsBusy, setSuggestionsBusy] = useState(false);

  const [deps, setDeps] = useState<Dep[] | null>(null);

  const [depBlocked, setDepBlocked] = useState(false);

  const [depAddOpen, setDepAddOpen] = useState(false);

  const [checklist, setChecklist] = useState<Checklist | null>(null);

  const [newCheckText, setNewCheckText] = useState("");

  const [subitems, setSubitems] = useState<Item[] | null>(null);

  const [newSubTitle, setNewSubTitle] = useState("");
  const [subPolicyDialog, setSubPolicyDialog] = useState(false); // YPJ-218 spec 0139:加子时父自有时间线策略
  // 已有子的父不持有自有时间线:时间线框禁用(spec 0139 定案)
  const timelineLocked = (subitems ?? []).length > 0;

  const [tab, setTab] = useState(0);

  const [back, setBack] = useState(() => resolveBack(null, key));

  // ?from= 白名单 token → 返回来源页(YPJ-31);不用 useSearchParams,免 Suspense 边界要求

  useEffect(() => {
    const from = new URLSearchParams(window.location.search).get("from");

    setBack(resolveBack(from, key));
  }, [key]);

  // release 挂接独立拉取(不并首屏 Promise.all,避免动脆弱的下拉菜单时序)
  useEffect(() => {
    if (!itemId) return;
    api<{ releaseId: string | null }>(`/api/items/${itemId}/release-membership`)
      .then((m) => setReleaseId(m.releaseId))
      .catch(() => setReleaseId(null));
  }, [itemId]);

  useEffect(() => {
    api<ReleaseLite[]>(`/api/projects/${key}/releases`)
      .then((rs) => setReleases(rs.filter((r) => r.status === "planned")))
      .catch(() => setReleases([]));
  }, [key]);

  async function setReleaseMembership(next: string | null) {
    try {
      const m = await api<{ releaseId: string | null }>(
        `/api/items/${itemId}/release-membership`,
        {
          method: "PUT",
          body: JSON.stringify({ releaseId: next }),
        },
      );
      setReleaseId(m.releaseId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "挂接失败");
    }
  }

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

    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));

    api<{ entries: TimeEntry[]; totalMinutes: number }>(
      `/api/items/${itemId}/time-entries`,
    )
      .then(setTimeLog)

      .catch(() => {});

    api<{ dependencies: Dep[]; blocked: boolean }>(
      `/api/items/${itemId}/dependencies`,
    )
      .then((d) => {
        setDeps(d.dependencies);
        setDepBlocked(d.blocked);
      })

      .catch(() => {});
  }, [key, itemId]);

  // YPJ-84:子条目 = 直接 children,复用全量列表客户端过滤(不加后端端点,spec 0071)

  const loadSubitems = useCallback(async () => {
    if (!itemId) return;

    try {
      const list = await api<Item[]>(`/api/projects/${key}/items`);

      setSubitems(list.filter((i) => i.parentItemId === itemId));
    } catch {
      // 同依赖区:页签内列表,加载失败静默
    }
  }, [key, itemId]);

  useEffect(() => {
    load().catch((e) => setError(e.message));

    loadSubitems();
  }, [load, loadSubitems]);

  // YPJ-257(spec 0168):桶内 consecutive 浏览——桶 = 看板列(boardOrder 序)→ backlog(number 序)→ planned L1 组(number 序);
  // spec 0098:board ∩ backlog = ∅,planned 组成员两处皆不在;多组命中取首个(createdAt 序,正常一票一 L1 组)
  const [nav, setNav] = useState<{
    prev: string | null;
    next: string | null;
    index: number;
    total: number;
  } | null>(null);

  useEffect(() => {
    if (!item) return;
    let dead = false;
    (async () => {
      const numeric = (a: Item, b: Item) =>
        a.number.localeCompare(b.number, undefined, { numeric: true });
      const byBoardOrder = (a: Item, b: Item) =>
        (a.boardOrder ?? Number.MAX_SAFE_INTEGER) -
          (b.boardOrder ?? Number.MAX_SAFE_INTEGER) || numeric(a, b);
      let list: Item[] | null = null;
      // 同看板 S7 assignee 过滤(单选 chip,localStorage 持久化,YPJ-257):导航只在过滤后的可见集内
      const f = localStorage.getItem(`yz.board.filter.${key}`) as Filter | null;
      const visible = (l: Item[]) =>
        !f || f === "all"
          ? l
          : f === "unassigned"
            ? l.filter((i) => !i.assignee)
            : f === "blocked"
              ? l.filter((i) => i.blocked)
              : l.filter((i) => i.assignee === f);
      const board = await api<{ items: Item[] }>(
        `/api/projects/${key}/board`,
      ).catch(() => null);
      const me = board?.items.find((i) => i.itemId === itemId);
      if (board && me) {
        // 用户拍板 2026-11-25:跨列连续浏览——状态按看板列序(workflow),列内 boardOrder 序,列尾接下一列首
        const rank = new Map(statuses.map((st, i) => [st.name, i]));
        list = visible(board.items).sort(
          (a, b) =>
            (rank.get(a.status) ?? Number.MAX_SAFE_INTEGER) -
              (rank.get(b.status) ?? Number.MAX_SAFE_INTEGER) ||
            byBoardOrder(a, b),
        );
      } else {
        const backlog = await api<Item[]>(`/api/projects/${key}/backlog`).catch(
          () => [],
        );
        if (visible(backlog).some((i) => i.itemId === itemId)) {
          list = visible(backlog).sort(numeric);
        } else {
          for (const g of sprints.filter((s) => s.status !== "completed")) {
            const members = await api<Item[]>(
              `/api/projects/${key}/groups/${g.sprintId}/items`,
            ).catch(() => []);
            if (visible(members).some((i) => i.itemId === itemId)) {
              list = visible(members).sort(numeric);
              break;
            }
          }
        }
      }
      if (dead) return;
      if (!list) {
        setNav(null);
        return;
      }
      const idx = list.findIndex((i) => i.itemId === itemId);
      setNav({
        prev: idx > 0 ? list[idx - 1].number : null,
        next: idx < list.length - 1 ? list[idx + 1].number : null,
        index: idx,
        total: list.length,
      });
    })();
    return () => {
      dead = true;
    };
  }, [item, itemId, key, sprints, statuses]);

  // Ctrl+←/→ 桶内导航;文本编辑焦点让位(spec 0168)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.ctrlKey || (e.key !== "ArrowLeft" && e.key !== "ArrowRight"))
        return;
      const el = e.target as HTMLElement | null;
      if (
        el &&
        (el.isContentEditable ||
          ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName))
      )
        return;
      if (!nav) return;
      const target = e.key === "ArrowLeft" ? nav.prev : nav.next;
      if (target) {
        e.preventDefault();
        router.push(`/p/${key}/i/${target}`);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [nav, key, router]);

  async function addSubitem() {
    if (!newSubTitle.trim()) return;

    // YPJ-218(spec 0139):父无子且自有时间线时,加子先弹策略选择(迁移默认);有子时时间线框已禁用,直接建
    if ((subitems ?? []).length === 0 && (startDate || dueDate)) {
      setSubPolicyDialog(true);
      return;
    }
    await createSubitem(newSubTitle.trim());
  }

  async function createSubitem(title: string) {
    try {
      await api(`/api/projects/${key}/items`, {
        method: "POST",

        body: JSON.stringify({
          title,
          parentItemId: itemId,
        }),
      });

      setNewSubTitle("");

      await loadSubitems();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    }
  }

  // 定案②迁移:子「{父标题}·原排期」承接父原时间线(POST 后 PATCH 补日期),父清空后再建新子
  async function addSubitemWithPolicy(policy: "ignore" | "migrate") {
    setSubPolicyDialog(false);
    const title = newSubTitle.trim();
    if (!title) return;

    try {
      if (policy === "migrate") {
        const legacy = (await api<{ itemId: string }>(
          `/api/projects/${key}/items`,
          {
            method: "POST",
            body: JSON.stringify({
              title: `${item?.title ?? "父 item"}·原排期`,
              parentItemId: itemId,
            }),
          },
        )) as { itemId: string };
        await api(`/api/items/${legacy.itemId}`, {
          method: "PATCH",
          body: JSON.stringify({ startDate, dueDate }),
        });
      }
      await api(`/api/items/${itemId}`, {
        method: "PATCH",
        body: JSON.stringify({ startDate: "", dueDate: "" }),
      });
      setStartDate("");
      setStartTime("");
      setDueDate("");
      setDueTime("");
      await createSubitem(title);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    }
  }

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

  async function addComment() {
    if (!newComment.trim()) return;

    await api(`/api/items/${itemId}/comments`, {
      method: "POST",
      body: JSON.stringify({ body: newComment.trim() }),
    });

    setNewComment("");

    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));
  }

  async function removeComment(commentId: string) {
    await api(`/api/comments/${commentId}`, { method: "DELETE" });

    setComments((prev) => prev.filter((c) => c.commentId !== commentId));
  }

  async function startTimer() {
    setError("");

    try {
      await api(`/api/items/${itemId}/time-entries`, {
        method: "POST",
        body: JSON.stringify({ minutes: null }),
      });

      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }

  async function loadSuggestions() {
    setError("");

    setSuggestionsBusy(true);

    try {
      setSuggestions(
        await api<SuggestedEntry[]>(
          `/api/items/${itemId}/time-entry-suggestions`,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    } finally {
      setSuggestionsBusy(false);
    }
  }

  async function logSuggestion(s: SuggestedEntry) {
    setError("");

    setSuggestionsBusy(true);

    try {
      await api(`/api/items/${itemId}/time-entries`, {
        method: "POST",

        body: JSON.stringify({
          minutes: null,
          startedAt: s.startedAt,
          endedAt: s.endedAt,
          note: s.note,
        }),
      });

      setSuggestions((prev) => (prev ?? []).filter((x) => x.ref !== s.ref));

      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    } finally {
      setSuggestionsBusy(false);
    }
  }

  async function stopTimer(entryId: string) {
    setError("");

    try {
      await api(`/api/time-entries/${entryId}/stop`, { method: "POST" });

      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }

  async function addManual() {
    const m = Number(manualMinutes);

    if (!m || m <= 0) return;

    setError("");

    try {
      await api(`/api/items/${itemId}/time-entries`, {
        method: "POST",

        body: JSON.stringify({ minutes: m, note: manualNote.trim() || null }),
      });

      setManualMinutes("");

      setManualNote("");

      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }

  async function addDependency(dependsOnItemId: string) {
    if (!dependsOnItemId) return;

    setError("");

    try {
      await api(`/api/items/${itemId}/dependencies`, {
        method: "POST",

        body: JSON.stringify({ dependsOnItemId }),
      });

      setDepAddOpen(false);

      const d = await api<{ dependencies: Dep[]; blocked: boolean }>(
        `/api/items/${itemId}/dependencies`,
      );

      setDeps(d.dependencies);
      setDepBlocked(d.blocked);
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
  }

  async function removeDep(rowId: string) {
    await api(`/api/dependencies/${rowId}`, { method: "DELETE" }).catch(
      () => {},
    );

    const d = await api<{ dependencies: Dep[]; blocked: boolean }>(
      `/api/items/${itemId}/dependencies`,
    );

    setDeps(d.dependencies);
    setDepBlocked(d.blocked);
  }

  async function reloadChecklist() {
    setChecklist(
      await api<Checklist>(`/api/items/${itemId}/checklist`).catch(() => null),
    );
  }

  async function addCheckEntry() {
    if (!newCheckText.trim()) return;

    setError("");

    try {
      await api(`/api/items/${itemId}/checklist`, {
        method: "POST",

        body: JSON.stringify({ text: newCheckText.trim() }),
      });

      setNewCheckText("");

      await reloadChecklist();
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
  }

  async function toggleCheckEntry(entryId: string, done: boolean) {
    await api(`/api/checklist/${entryId}`, {
      method: "PATCH",
      body: JSON.stringify({ done }),
    }).catch(() => {});

    await reloadChecklist();
  }

  async function removeCheckEntry(entryId: string) {
    await api(`/api/checklist/${entryId}`, { method: "DELETE" }).catch(
      () => {},
    );

    await reloadChecklist();
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
        open={warnMsg !== null}
        autoHideDuration={8000}
        onClose={() => setWarnMsg(null)}
        data-testid="item-warnings"
      >
        <Alert severity="warning" onClose={() => setWarnMsg(null)}>
          <span style={{ whiteSpace: "pre-line" }}>{warnMsg}</span>
        </Alert>
      </Snackbar>
    </PageShell>
  );
}

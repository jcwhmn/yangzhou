"use client";

import { Button, Stack, Typography } from "@mui/material";
import { PageShell } from "@/components/PageShell";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import {
  getSprint,
  sprintAction,
  assignSprint,
  createSprint,
  listParentCandidates,
  updateGroupParent,
} from "@/lib/sprints";
import {
  type Candidate,
  type Item,
  type Sprint,
  type Status,
  type TypeWorkflow,
  type Disp,
} from "./types";
import { ChartBy } from "./components/ChartBy";
import { useSprintData } from "./hooks/useSprintData";
import { SprintChartSection } from "./components/SprintChartSection";
import { AddMembersDialog } from "./components/AddMembersDialog";
import { ConfirmFlowDialog } from "./components/ConfirmFlowDialog";
import { DispositionDialog } from "./components/DispositionDialog";
import { SprintHeader } from "./components/SprintHeader";
import { SprintItemsTable } from "./components/SprintItemsTable";

/**
 * V12-S3 Sprint 落地页(spec 0010,grill 裁决 1/2):
 * 头部 = 名称/日期/状态切换(planned→active→completed)/ 表格(V19 YPJ-41 移除看板视图,看板语义归项目级看板,spec 0023/0034);
 * 空 sprint 给出指派路径提示;未知 sprint id → 页内错误态 + 返回上一页(用户反馈 2026-09-29)。
 * 完成的 sprint 即历史回看:items 为完成时仍在其列的成员关系。
 */
export default function SprintPage() {
  const { key, id } = useParams<{ key: string; id: string }>();
  const router = useRouter();
  const {
    sprint,
    setSprint,
    sprintVer,
    items,
    setItems,
    statuses,
    groups,
    l1Key,
    typeName,
    wf,
    parentSel,
    error,
    setError,
    gone,
  } = useSprintData(key, id);
  const [addOpen, setAddOpen] = useState(false);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [confirmFlow, setConfirmFlow] = useState<
    null | "start" | "complete" | "reopen"
  >(null);
  // 0106:组完成后的非终态成员处置(rows 默认全转入目标组,逐行可覆写回 Backlog;D2/D3)
  const [disp, setDisp] = useState<Disp | null>(null);

  async function changeStatus(status: string) {
    if (!sprint) return;
    try {
      // spec 0097 S2/YPJ-120:状态变更按当前状态到目标态的出边 function 名走
      // (builtin 工作流=start/complete;自定义默认三态循环=switch,spec 0114 T163B)
      const fn = edgeFn(status);
      if (!fn) throw new Error(`当前状态无到 ${status} 的出边`);
      const s = await sprintAction(key, id, fn);
      sprintVer.current += 1;
      setSprint(s);
      // YPJ-74:sidebar 重拉由 sprintAction 内部广播(0105 收口)
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    }
  }

  /** spec 0113:改挂父 epic;成功后广播侧栏重拉 + 刷新本页。置空不存在(选择器无空选项,后端兜底 400)。 */
  async function reparent(target: string) {
    if (!sprint || !target) return;
    try {
      await updateGroupParent(key, id, target);
      window.dispatchEvent(new Event("sprints-changed"));
      setSprint(await getSprint(key, id));
    } catch (e) {
      setError(e instanceof Error ? e.message : t.sprintPage.reparentFailed);
    }
  }

  // V13-S4:状态单向——开始/完成各一步;开始时若已有进行中 sprint 则提示(不禁止并存)
  function startSprint() {
    if (
      groups.some(
        (s) =>
          s.typeKey === sprint?.typeKey &&
          s.status === "active" &&
          s.sprintId !== id,
      )
    )
      setConfirmFlow("start");
    else void doStart();
  }
  async function doStart() {
    await changeStatus("active");
    setConfirmFlow(null);
  }
  /** 0110:全 Done 完成路径(确认弹窗「确认完成」)——无处置,直迁状态。 */
  async function completeEmpty() {
    await changeStatus("completed");
    setConfirmFlow(null);
  }
  function completeSprint() {
    // 0110:有非终态成员直接弹处置(取消|进 Backlog|进「组名」);全 Done 走确认弹窗
    const nonFinal = (items ?? []).some(
      (it) =>
        statuses.find((st) => st.name === it.status)?.statusType !== "FINAL",
    );
    if (nonFinal) void doComplete();
    else setConfirmFlow("complete");
  }
  async function doComplete() {
    // 0110:完成+处置同一事务,状态迁移随处置一次性提交
    const nonFinal = (items ?? []).filter(
      (it) =>
        statuses.find((st) => st.name === it.status)?.statusType !== "FINAL",
    );
    setConfirmFlow(null); // 开处置前关确认弹窗,避免双 dialog 叠置
    // spec 0114:候选=同类型组;计划中再限同父(同季)。L1 无父(null==null)退化为全类型,平面行为不变
    const others = groups.filter(
      (g) => g.typeKey === sprint?.typeKey && g.sprintId !== id,
    );
    const actives = others.filter((s) => s.status === "active");
    const planned = others.filter(
      (s) =>
        s.status === "planned" &&
        (s.parentGroupId ?? null) === (sprint?.parentGroupId ?? null),
    );
    const tn = typeName ?? sprint?.typeKey ?? "";
    let n = others.length + 2; // 含本组(others 不含自身):新建的是「下一号」
    while (others.some((s) => s.name === `${tn} ${n}`)) n += 1;
    setDisp({
      rows: nonFinal.map((it) => ({
        itemId: it.itemId,
        title: it.title,
        toBacklog: false,
      })),
      actives,
      planned,
      typeName: tn,
      pick: (actives[0] ?? planned[0])?.sprintId ?? "",
      newName: `${tn} ${n}`,
    });
  }

  /** 0110:处置计划随完成单次提交——状态迁移+处置同一后端事务;失败整体回滚,弹窗保留可改计划重试。 */
  async function confirmDisposition() {
    if (!disp || busy) return;
    const { actives, planned, pick } = disp;
    setBusy(true);
    let target: string | null = null;
    try {
      if (actives.length > 0) {
        target = pick || actives[0].sprintId;
      } else if (planned.length === 1) {
        await sprintAction(key, planned[0].sprintId, startFnOf());
        target = planned[0].sprintId;
      } else if (planned.length > 1) {
        await sprintAction(key, pick, startFnOf());
        target = pick;
      } else {
        const name = disp.newName.trim() || `${disp.typeName} 1`;
        const existing = groups.find(
          (s) => s.typeKey === sprint?.typeKey && s.name === name,
        );
        target = (
          existing ??
          (await createSprint(key, {
            name,
            startDate: null,
            endDate: null,
            // spec 0112:处置新建组继承被完成 sprint 的父 epic(否则悬空)
            parentGroupId: sprint?.parentGroupId ?? null,
          }))
        ).sprintId;
        await sprintAction(key, target, startFnOf());
      }
    } catch (err) {
      // 目标组未就绪 → 事务未发生,组未完成;改计划重试
      setError(err instanceof Error ? err.message : t.board.startFailed);
      setBusy(false);
      return;
    }
    try {
      const s = await sprintAction(key, id, finalFnOf(), {
        dispositions: disp.rows.map((r) => ({
          itemId: r.itemId,
          targetGroupId: r.toBacklog ? null : target,
        })),
      });
      sprintVer.current += 1;
      setSprint(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.startFailed);
      setBusy(false);
      return;
    }
    setItems(await api<Item[]>(`/api/projects/${key}/groups/${id}/items`));
    setDisp(null);
    setConfirmFlow(null);
    setBusy(false);
  }

  /** 0110:「进 Backlog」——全部非终态成员显式回池,随完成同事务落库(旧行清理由 replaceMembership 承担)。 */
  async function doBacklogAll() {
    if (!disp || busy) return;
    setBusy(true);
    try {
      const s = await sprintAction(key, id, finalFnOf(), {
        dispositions: disp.rows.map((r) => ({
          itemId: r.itemId,
          targetGroupId: null,
        })),
      });
      sprintVer.current += 1;
      setSprint(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.board.startFailed);
      setBusy(false);
      return;
    }
    setItems(await api<Item[]>(`/api/projects/${key}/groups/${id}/items`));
    setDisp(null);
    setConfirmFlow(null);
    setBusy(false);
  }

  // spec 0097 S3/YPJ-120:按钮 = 当前状态出边(backward 仅 CLI/API 可及);
  // 自定义默认工作流出边 function=switch(T163B),按目标态路由到既有语义
  function edgeFn(target: string): string | undefined {
    return wf
      .find((st) => st.name === sprint?.status)
      ?.transitions.find((tr) => tr.target === target)?.function;
  }
  // spec 0114:激活/完成的出边按「目标态 statusType」解析——自定义工作流 fn=switch,不写 start/complete 字面量
  function startFnOf(): string {
    const start = wf.find((s) => s.statusType === "START");
    const activeName = wf.find((s) => s.statusType === "ACTIVE")?.name;
    return (
      start?.transitions.find((tr) => tr.target === activeName)?.function ??
      "start"
    );
  }
  function finalFnOf(): string {
    const finalName = wf.find((s) => s.statusType === "FINAL")?.name;
    return (finalName ? edgeFn(finalName) : undefined) ?? "complete";
  }
  function actionClick(fn: string, target?: string) {
    if (fn === "start") return startSprint();
    if (fn === "complete") return completeSprint();
    // 0108:重开低显著(outlined 由确认弹窗双闸防误触)+确认制
    if (fn === "reopen") return setConfirmFlow("reopen");
    // 自定义默认三态循环(switch):按出边目标态路由到既有语义
    if (target === "active") return startSprint();
    if (target === "completed") return completeSprint();
    if (target === "planned") return setConfirmFlow("reopen");
  }
  async function doReopen() {
    // 0108:重开出边按实际 function(reopen / 自定义工作流的 switch)
    const fn = edgeFn("planned") ?? "reopen";
    const s = await sprintAction(key, id, fn);
    sprintVer.current += 1;
    setSprint(s);
    setConfirmFlow(null);
  }

  // V13-S3:拉入/移出同一对话框——列出非终态 item 多选,确认后逐条走既有 PUT /items/{id}/sprint
  async function openAdd() {
    try {
      // V13-S4:范围 = Backlog + 本 sprint 成员(其它 sprint 的 item 不列,挪动走详情下拉);
      // YPJ-216:候选带预估快照(已有预估的票不重录,重录会触发互算改 due)
      const backlogItems = await api<Candidate[]>(
        `/api/projects/${key}/backlog`,
      );
      const members = (items ?? []).map((i) => ({
        itemId: i.itemId,
        number: i.number,
        title: i.title,
        status: i.status,
        assignee: i.assignee,
        estimateValue: i.estimateValue,
        estimateUnit: i.estimateUnit,
      }));
      const memberIds = new Set(members.map((m) => m.itemId));
      setCandidates([
        ...members,
        ...backlogItems.filter((b) => !memberIds.has(b.itemId)),
      ]);
      setSelected(new Set(members.map((m) => m.itemId)));
      setAddOpen(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }

  // YPJ-216(spec 0164 途径④):对话框行内补录的预估先落票(已有预估的票跳过,避免互算改 due),再逐条入组
  async function applyMembership(
    estimates: Record<string, { v: string; u: string }> = {},
  ) {
    setBusy(true);
    try {
      for (const [itemId, e] of Object.entries(estimates)) {
        const already =
          candidates.find((c) => c.itemId === itemId)?.estimateValue != null;
        if (already) continue; // 已有预估的票不重录(重录触发互算改 due)
        await api(`/api/items/${itemId}`, {
          method: "PATCH",
          body: JSON.stringify({ estimateValue: e.v, estimateUnit: e.u }),
        });
      }
      const prev = new Set((items ?? []).map((i) => i.itemId));
      for (const c of candidates) {
        const now = selected.has(c.itemId);
        if (prev.has(c.itemId) === now) continue;
        await assignSprint(key, c.itemId, now ? id : null);
      }
      setAddOpen(false);
      setItems(await api<Item[]>(`/api/projects/${key}/groups/${id}/items`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "更新失败");
    } finally {
      setBusy(false);
    }
  }

  const toggleCandidate = (itemId: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  const pickDisp = (sprintId: string) => {
    if (disp) setDisp({ ...disp, pick: sprintId });
  };
  const renameDisp = (newName: string) => {
    if (disp) setDisp({ ...disp, newName });
  };
  const toggleRowDisp = (idx: number) => {
    if (!disp) return;
    const r = disp.rows[idx];
    setDisp({
      ...disp,
      rows: disp.rows.toSpliced(idx, 1, { ...r, toBacklog: !r.toBacklog }),
    });
  };
  const closeDisp = () => setDisp(null);
  const confirmFlowConfirm = () => {
    if (confirmFlow === "start") void doStart();
    else if (confirmFlow === "reopen") void doReopen();
    else void completeEmpty();
  };

  // 页内错误态:未知 sprint id 等;给返回按钮,不依赖浏览器后退(用户反馈)
  if (gone) {
    return (
      <PageShell>
        <Stack spacing={2} alignItems="center">
          <Typography variant="h5">Sprint 不存在或已被删除</Typography>
          <Button variant="contained" onClick={() => router.back()}>
            ← 返回上一页
          </Button>
        </Stack>
      </PageShell>
    );
  }

  return (
    <PageShell>
      <Button size="small" onClick={() => router.back()} sx={{ mb: 1 }}>
        ← 返回
      </Button>
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      {!sprint && !gone && (
        <Typography color="text.secondary">加载中…</Typography>
      )}
      {sprint && (
        <>
          <SprintHeader
            sprint={sprint}
            items={items}
            wf={wf}
            l1Key={l1Key}
            parentSel={parentSel}
            typeName={typeName}
            onReparent={(target) => void reparent(target)}
            onAction={actionClick}
            onOpenAdd={openAdd}
          />

          <SprintChartSection
            projectKey={key}
            groupId={id}
            items={items}
            statusOrder={statuses.map((s) => s.name)}
          />

          {items !== null && items.length > 0 && (
            <SprintItemsTable
              projectKey={key}
              groupId={id}
              items={items}
              onAssigneeChanged={() =>
                api<Item[]>(`/api/projects/${key}/sprints/${id}/items`)
                  .then(setItems)
                  .catch(() => {})
              }
            />
          )}
          <AddMembersDialog
            open={addOpen}
            projectKey={key}
            candidates={candidates}
            selected={selected}
            busy={busy}
            onToggle={toggleCandidate}
            onClose={() => setAddOpen(false)}
            onApply={applyMembership}
          />
          <ConfirmFlowDialog
            confirmFlow={confirmFlow}
            groupId={id}
            typeName={typeName}
            sprint={sprint}
            groups={groups}
            items={items}
            statuses={statuses}
            onClose={() => setConfirmFlow(null)}
            onConfirm={confirmFlowConfirm}
          />
          {/* 0106:非终态成员处置——目标组决策树 + 逐行覆写回 Backlog(D2/D3);0107 行内显式按钮 + 提交前摘要(方案A,消除 checkbox 误勾事故) */}
          <DispositionDialog
            disp={disp}
            sprintName={sprint?.name ?? ""}
            busy={busy}
            onClose={closeDisp}
            onPick={pickDisp}
            onNewName={renameDisp}
            onToggleRow={toggleRowDisp}
            onBacklogAll={doBacklogAll}
            onConfirm={confirmDisposition}
          />
        </>
      )}
    </PageShell>
  );
}

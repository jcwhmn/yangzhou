// YPJ-275(spec 0167 §五/§七):关闭理由·前端——用户主路径:
// 设开关 → 拖卡进 Done 弹窗 → 选不做+备注 → 卡片标识 → 详情页理由可见可改 → 改回完成;
// 开关关不弹(现状零打扰)。后端分支(枚举白名单/400/reopen 清空)由 CloseReasonApiTest 覆盖,此处不重复。
import { expect, test } from "./support/fixtures";
import { apiCall, apiAssignMe, apiCreateItem } from "./support/helpers";

test.describe("YPJ-275 关闭理由(前端)", () => {
  // S0(无 level-1 类型)= 无 sprint 概念,建票即可上看板(同 board-order.spec 先例;非 S0 需 active sprint 才可挂板)
  test.use({ s0: true });

  const TITLE = "CR 关闭甲";

  /** 拖卡进列:locator.dragTo 触发不了 Chromium 原生 HTML5 DnD(实跑 2 连挂,卡片原地不动),
   *  故同一次 evaluate 内用共享 DataTransfer 派发 dragstart→drop——React 合成事件靠原生冒泡触发,
   *  moveTo 读 dataTransfer.getData 拿到 itemId;断言仍只看界面结果(A48 口径)。 */
  async function dragCardToColumn(
    page: import("@playwright/test").Page,
    title: string,
    targetText: string,
  ) {
    await page.evaluate(
      ({ title, targetText }) => {
        const card = [...document.querySelectorAll(".MuiCard-root")].find(
          (el) => el.textContent?.includes(title),
        );
        const target = [...document.querySelectorAll("h6")].find((el) =>
          el.textContent?.includes(targetText),
        );
        if (!card || !target) throw new Error("拖拽源/目标没找到");
        const dt = new DataTransfer();
        const opts = { bubbles: true, cancelable: true, dataTransfer: dt };
        card.dispatchEvent(new DragEvent("dragstart", opts));
        target.dispatchEvent(new DragEvent("drop", opts));
        card.dispatchEvent(new DragEvent("dragend", opts));
      },
      { title, targetText },
    );
  }

  /** 建票并上看板（默认落 To Do）；自指派保持确定性——D7 豁免后裸票也能直迁终态，但旧镜像仍拦 */
  async function seedItem(projectKey: string, title: string) {
    const id = await apiCreateItem(projectKey, title);
    await apiAssignMe(id);
    await apiCall("PUT", `/api/items/${id}/board-membership`, {
      onBoard: true,
    });
    return id;
  }

  async function statusId(projectKey: string, name: string) {
    const p = await apiCall<{
      statuses: { statusId: string; name: string }[];
    }>("GET", `/api/projects/${projectKey}`);
    const s = p.statuses.find((x) => x.name === name);
    if (!s) throw new Error(`状态 ${name} 不存在`);
    return s.statusId;
  }

  test("开关开_拖进Done弹窗_不做加备注_卡片与详情页标识_改回完成", async ({
    page,
    projectKey,
  }) => {
    // To Do(requireCloseReason=true)→ Done(FINAL):命中弹窗分支
    await apiCall(
      "PATCH",
      `/api/statuses/${await statusId(projectKey, "To Do")}`,
      {
        requireCloseReason: true,
      },
    );
    const itemId = await seedItem(projectKey, TITLE);
    await page.goto(`/p/${projectKey}/board`);
    await expect(
      page.locator(".MuiCard-root", { hasText: TITLE }),
    ).toBeVisible();

    // 拖卡进 Done 列 → 弹窗,默认选「完成」
    await dragCardToColumn(page, TITLE, "Done(0)");
    const dialog = page.getByRole("dialog");
    await expect(
      dialog.getByRole("combobox", { name: "关闭原因" }),
    ).toBeVisible();
    await expect(
      dialog.getByRole("combobox", { name: "关闭原因" }),
    ).toHaveValue("完成");

    // 选不做 → 备注框展开 → 填备注 → 确认
    await page.getByRole("combobox", { name: "关闭原因" }).click();
    await page.getByRole("option", { name: "不做" }).click();
    await dialog.getByLabel("备注(可选)").fill("客户撤了");
    await dialog.getByRole("button", { name: "确认关闭" }).click();

    // 卡片落 Done 列,末尾标识「非完成关闭:不做」;弹窗已关
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(
      page.locator(".MuiCard-root", { hasText: TITLE }),
    ).toContainText("非完成关闭:不做");

    // 详情页:理由 chip 可见(Tooltip 使 accessible name =「修改关闭理由」,文案才是理由)
    await page.goto(`/p/${projectKey}/i/${itemId}`);
    const reasonChip = page.getByRole("button", { name: "修改关闭理由" });
    await expect(reasonChip).toBeVisible();
    await expect(reasonChip).toHaveText("不做");
    await reasonChip.click();
    const editDialog = page.getByRole("dialog");
    await expect(
      editDialog.getByRole("combobox", { name: "关闭原因" }),
    ).toHaveValue("不做");
    await expect(editDialog.getByLabel("备注(可选)")).toHaveValue("客户撤了");
    await page.getByRole("combobox", { name: "关闭原因" }).click();
    await page.getByRole("option", { name: "完成" }).click();
    await editDialog.getByRole("button", { name: "保存" }).click();

    // chip 文案变「完成」(done 灰,理由不再带色);accessible name 仍是「修改关闭理由」
    await expect(reasonChip).toHaveText("完成");
  });

  test("开关关_拖进Done不弹_直接迁移零打扰", async ({ page, projectKey }) => {
    const itemId = await seedItem(projectKey, "CR 关闭乙");
    await page.goto(`/p/${projectKey}/board`);
    await expect(
      page.locator(".MuiCard-root", { hasText: "CR 关闭乙" }),
    ).toBeVisible();

    // 等 PATCH 落定再查库——乐观更新让 Done(1) 先于 PATCH 可见,直查拿到旧状态
    const patchDone = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/items/${itemId}`) &&
        r.request().method() === "PATCH",
    );
    await dragCardToColumn(page, "CR 关闭乙", "Done(0)");
    const resp = await patchDone;
    expect(resp.status()).toBe(200);

    await expect(page.getByRole("dialog")).toHaveCount(0);
    // 迁移生效:Done 列计数变 1;现状零打扰:后端写 done,卡片无理由标识
    await expect(page.getByText("Done(1)")).toBeVisible();
    const it = await apiCall<{ closeReason: string | null }>(
      "GET",
      `/api/items/${itemId}`,
    );
    expect(it.closeReason).toBe("done");
  });
});

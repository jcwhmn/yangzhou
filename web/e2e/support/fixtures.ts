// 自定义 fixture:projectKey = 每用例独享的项目(API 预置,唯一 key,并行安全)
// 测试库(yangzhou_test)为专用可弃库,不做项目级删除清理;CI 每次全新
import { expect, test as base } from "@playwright/test";
import { apiCreateProject, injectToken, uniqueKey } from "./helpers";

type Fixtures = {
  /** 独享项目 key */
  projectKey: string;
  /** 真 S0(无 level-1 类型)项目;默认 false=补种 sprint 类型(spec 0117 后拉入按有无 L1 类型分流) */
  s0: boolean;
};

export const test = base.extend<Fixtures>({
  s0: [false, { option: true }],
  projectKey: async ({ page, s0 }, use) => {
    await injectToken(page);
    const key = uniqueKey();
    await apiCreateProject(key, { s0 });
    await use(key);
  },
});

export { expect };

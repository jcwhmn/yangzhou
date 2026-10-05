// 自定义 fixture:projectKey = 每用例独享的项目(API 预置,唯一 key,并行安全)
// 测试库(yangzhou_test)为专用可弃库,不做项目级删除清理;CI 每次全新
import { expect, test as base } from "@playwright/test";
import { apiCreateProject, injectToken, uniqueKey } from "./helpers";

type Fixtures = {
  /** 独享项目 key */
  projectKey: string;
};

export const test = base.extend<Fixtures>({
  projectKey: async ({ page }, use) => {
    await injectToken(page);
    const key = uniqueKey();
    await apiCreateProject(key);
    await use(key);
  },
});

export { expect };

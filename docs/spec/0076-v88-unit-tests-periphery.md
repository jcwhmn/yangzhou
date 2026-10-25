# 0076 — YPJ-88 单测补齐·周边域(comment/recyclebin/attachment/favorite/notification)

> 票:YPJ-88 · 影响:`backend/api/src/test`(纯新增测试,零生产代码改动)

## 1. 需求

分域补齐第三票(接 0074/0075):四个 service 全分支单测,共 **35 例**。comment 与 recyclebin 此前 0 测试。

- `CommentServiceUnitTest`(6):list 最新在前/404、create 404/trim+notifyComment 扇出、delete 404/删除
- `FavoriteServiceUnitTest`(6):add 404/幂等 noop/新增、remove 静默/删除、list 跳过已删项目
- `NotificationServiceUnitTest`(12):收件人推导(assignee+creator+评论者去重、actor 排除、actor null 不排除)、自指派不通知、assigned 仅收件人;list 未读在前+50 截断+「下一列」action 现算+final 无 action;unreadCount;markRead 他人 404/幂等;readAll 只盖未读
- `AttachmentServiceUnitTest`(11):register 404/文件名非法/扩展白名单/大小边界(0 与超限)/objectKey 清洗+预签名、list 映射、download 预签名、跨 item 404、S3 删除失败不影响行删

### 非目标

- **recyclebin 不新建测试文件**:它是纯转发 controller(list/restore/purge 全在 ItemService,已被 ItemServiceUnitTest「delete/restore/purge/recycleBin」段覆盖)
- S3ObjectStorage 真实 AWS SDK 调用不测(集成轨/手工覆盖)

## 2. 详细设计

同 0074/0075 样板。新增踩坑沉淀:

- mockk `every { mock.prop } returns v`(只 stub 属性 getter)对 data class 无效——直接 stub 方法返回整个实体(`every { memberService.current() } returns me`),id 可空性也随之解决
- 被 service `copy` 后回存的场景(如 markRead),stub 要用 `answers { n = firstArg(); n }` 让第二次读取拿到已更新状态,否则幂等分支测不出来

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| 1 | `gradle :api:test` | 全绿 |
| 2 | `gradle build` | 绿 |

## 4. As-built

- 四文件 35 例全绿;`gradle build` 绿;零生产代码改动
- NotificationServiceUnitTest 的 item.statusObjectId 需与 status 表 stub 同 objectId(实例不同 objectId 不同,曾致 action 断言 null)

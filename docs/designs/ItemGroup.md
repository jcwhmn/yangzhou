
# Diagram

## workflow

```plantuml
@startuml

hide empty members

class Workflow{
  statuses
  currentStatus
  action
}

class WorkflowStatus{
  name
  statusType
  transitions
}

class WorkflowAction {
  name
  displayName
  icon
  functionName？
}

enum WorkflowStatusType{
  isStart
  isFinal
  isActive
}

class Transition{
  action
  target
}

class GroupWorkflow
class ItemWorkflow

Workflow <-- ItemWorkflow
Workflow <-- GroupWorkflow
Workflow "owns" o-- WorkflowStatus
Workflow - WorkflowStatus: currentStatus
WorkflowStatus - WorkflowStatusType
Workflow o-- WorkflowAction

WorkflowStatus o-- Transition
Transition - WorkflowAction
Transition - WorkflowStatu: target


@enduml
```


## ItemGroup

```plantuml
@startuml

hide empty members

class Item
class ItemGroup{
  isLeaf
  level
}
class GroupWorkflow
class ItemWorkflow
class Workflow
class Project
object Sprint
object TimeLine


Project "1" -- "0..1" ItemGroup
ItemGroup "1" o- "0..*" Item
ItemGroup "1" --> "1" GroupWorkflow
Item "1" -->  "1" ItemWorkflow
ItemWorkflow --> Workflow
GroupWorkflow --> Workflow
Item "0" o- "0..*" Item 
Sprint "instance of" -> ItemGroup
TimeLine "instance of" --> ItemGroup
TimeLine o-- Sprint

@enduml
```

here, TimeLine, and Sprint are instance object of ItemGroup, are user custom defined. also, User may define Phase, Peroid as their own item groups used in their project


# description

## workflow

workflow is the parent of item workflow and item group workflow

workflow owns 1 to more status
workflow owns 1 to more actions
status owns 1 to more transitions
transtion belongs to 1 status
transtion has one action, once action is triggered, workflow will transition to target status. action must be one of actions of workflow, so, when workflow is in a give status, all allowed actions are actions of its transition

each status must be in a give WorkflowStatusType. 
workflow must have at least isStart type status, and at least isFinal type status

now, we may not be able to create user customized workflow action, so we should define some functions, and map workflow actions to thse functions, like Complete, Start, Switch, Forward, Backward, Cancel.
so, we have a list of predefined actions. action in transition must choose from the list, and target stasut must choose from status list of the workflow

## item group

assume we have 2 level of item groups, level 1 is the level which is nearest items, it means it contains items.level2 is the parent of level1, and so on. for example: sprint is level1, Timeline is level2.

3 cases:
### 2 levels

we have 2 levels item groups, like Timeline -> Sprint, or 期 -> 主题, 
for example for we a demo data:
level2 item groups:
```
Timeline 26-2
Timeline 26-3
Timeline 26-4
```

now, Timeline 26-3 is an active Timeline, means it's neither isStart now isFinal, but isActive. and Timeline 26-2 is Done, Timeline 26-4 is Todo

#### display
I'd like to show Timeline 26-2, 26-3 and 26-4 in Side directly, and show sprints which belongs to Timeline 26-3 in side bar, but hide other sprints. meantime, we should have a + before Timeline 26-2, Timeline 26-4 like:
```
+ Timeline 26-2
- Timeline 26-3
  Sprint 3-1
  Sprint 3-2
  Sprint 3-3
+ Timeline 26-4  
```
click + before Timeline, shall unfold Timeline, show list of sprints belongs to the Timeline

#### operation

we may not deal with Timeline directly, instead, we handle sprint - which is the leaf item group of item. when we open, or close sprint, Timeline status should be updated accordingly.

### 1 levels

Now, all business logical will be at ItemGroupService/ItemGroupController, but 'Sprint' is the name of ItemGroup, 'Start Sprint' and 'Complete Sprint' are name of action, they're configured, not hardcoded.

in sidebar, Timeline block will be removed. 

### no itemGroup

item group的目的是要管理过多的items。在一个没有item group的系统里，所有的items堆到看板里，导致很难快速定位到我们需要的items，我们使用linear管理项目的后期就发生的这种情况，我需要仔细的找才能找到我需要的item。有了backlog可以缓解还重来没有in progress的items的问题，但对于进行中和done的项目，没法避免堆砌在看板上。我曾经的想法是使用搜索，hide done item的方法来提高关心的item的可见性。而现在这又是我们当前这个no item group所面对的问题。系统是否能no itemgrou？如果能no itemgrou，如何管理？
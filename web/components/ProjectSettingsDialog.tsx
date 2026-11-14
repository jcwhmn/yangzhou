"use client";

// YPJ-187:项目设置收敛单一入口——左侧节菜单 + 右侧内容区(各节复用既有面板,embedded 态)
import {
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  List,
  ListItemButton,
  ListItemText,
} from "@mui/material";
import { useState } from "react";
import { GithubSettingsPanel } from "@/components/GithubSettingsPanel";
import { GroupTypesPanel } from "@/components/GroupTypesPanel";
import { ItemTypePanel } from "@/components/ItemTypePanel";
import { ProjectMembersPanel } from "@/components/ProjectMembersPanel";
import { ProjectSettingsPanel } from "@/components/ProjectSettingsPanel";
import { WorkflowEditor } from "@/components/WorkflowEditor";
import { t } from "@/lib/texts";

const SECTIONS = [
  { id: "basic", label: t.settings.title },
  { id: "workflow", label: t.wf.title },
  { id: "members", label: t.membersPanel.title },
  { id: "itemTypes", label: t.itemTypes.title },
  { id: "groupTypes", label: t.groupTypes.title },
  { id: "github", label: t.gh.panelTitle },
  { id: "export", label: t.settings.exportTitle },
] as const;

type SectionId = (typeof SECTIONS)[number]["id"];

export function ProjectSettingsDialog({
  projectKey,
  open,
  onClose,
  onExport,
  onFlagsChanged,
  onProjectChanged,
  onGroupTypesClosed,
}: {
  projectKey: string;
  open: boolean;
  onClose: () => void;
  onExport: () => void;
  onFlagsChanged: () => void;
  onProjectChanged: () => void;
  onGroupTypesClosed: () => void;
}) {
  const [section, setSection] = useState<SectionId>("basic");

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{t.settings.title}</DialogTitle>
      <DialogContent dividers sx={{ display: "flex", gap: 2, p: 0 }}>
        <List
          dense
          sx={{
            width: 160,
            flexShrink: 0,
            borderRight: 1,
            borderColor: "divider",
            py: 0,
          }}
        >
          {SECTIONS.map((s) => (
            <ListItemButton
              key={s.id}
              selected={section === s.id}
              onClick={() => setSection(s.id)}
            >
              <ListItemText primary={s.label} />
            </ListItemButton>
          ))}
        </List>
        <Box sx={{ flex: 1, minWidth: 0, p: 2 }}>
          {section === "basic" && (
            <ProjectSettingsPanel
              embedded
              projectKey={projectKey}
              open={open}
              onClose={onClose}
              onChanged={onFlagsChanged}
            />
          )}
          {section === "workflow" && (
            <WorkflowEditor
              embedded
              projectKey={projectKey}
              open={open}
              onClose={onClose}
              onChanged={onProjectChanged}
            />
          )}
          {section === "members" && (
            <ProjectMembersPanel
              embedded
              projectKey={projectKey}
              open={open}
              onClose={onClose}
              onChanged={onProjectChanged}
            />
          )}
          {section === "itemTypes" && (
            <ItemTypePanel
              embedded
              projectKey={projectKey}
              open={open}
              onClose={onClose}
            />
          )}
          {section === "groupTypes" && (
            <GroupTypesPanel
              embedded
              projectKey={projectKey}
              open={open}
              onClose={onGroupTypesClosed}
            />
          )}
          {section === "github" && (
            <GithubSettingsPanel
              embedded
              projectKey={projectKey}
              open={open}
              onClose={onClose}
            />
          )}
          {section === "export" && (
            <Box sx={{ p: 2 }}>
              <ListItemButton
                onClick={onExport}
                sx={{ display: "inline-flex", width: "auto" }}
              >
                <ListItemText primary={t.settings.exportXlsx} />
              </ListItemButton>
            </Box>
          )}
        </Box>
      </DialogContent>
    </Dialog>
  );
}

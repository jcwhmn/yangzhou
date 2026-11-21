"use client";

import { Box, Button, Stack, TextField, Typography } from "@mui/material";
import { type CommentDto } from "../types";

/** CommentsTab(YPJ-251 自 page.tsx 搬出;评论数据 page 统一加载经 props 下发,动作回调上行) */
export function CommentsTab({
  comments,

  newComment,

  onCommentChange,

  onAdd,

  onRemove,
}: {
  comments: CommentDto[];

  newComment: string;

  onCommentChange: (v: string) => void;

  onAdd: () => void;

  onRemove: (commentId: string) => void;
}) {
  return (
    <>
      <Typography variant="h6" sx={{ mb: 1 }}>
        评论
      </Typography>

      <Stack spacing={1} sx={{ mb: 0 }}>
        {comments.map((c) => (
          <Box
            key={c.commentId}
            sx={{
              p: 1.5,
              bgcolor: "background.paper",
              borderRadius: 1,
              border: "1px solid",
              borderColor: "divider",
            }}
          >
            <Stack
              direction="row"
              justifyContent="space-between"
              alignItems="center"
            >
              <Typography variant="caption" color="text.secondary">
                {c.author} · {new Date(c.createdAt).toLocaleString("zh-CN")}
              </Typography>

              <Button
                size="small"
                color="error"
                onClick={() => onRemove(c.commentId)}
              >
                删除
              </Button>
            </Stack>

            <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
              {c.body}
            </Typography>
          </Box>
        ))}
      </Stack>

      <Stack direction="row" spacing={1}>
        <TextField
          size="small"

          placeholder="写评论…"

          value={newComment}

          onChange={(e) => onCommentChange(e.target.value)}

          fullWidth

          multiline

          minRows={4}

          maxRows={8}

          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && newComment.trim()) {
              e.preventDefault();

              onAdd();
            }
          }}
        />

        <Button
          variant="outlined"
          onClick={onAdd}
          disabled={!newComment.trim()}
        >
          发送
        </Button>
      </Stack>
    </>
  );
}

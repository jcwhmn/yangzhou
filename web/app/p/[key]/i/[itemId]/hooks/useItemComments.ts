// YPJ-257 页面重构(纯结构):评论域状态与动作自 page 下沉
import { useCallback, useEffect, useState } from "react";

import { api } from "@/lib/api";

import type { CommentDto } from "../types";

export function useItemComments(itemId: string) {
  const [comments, setComments] = useState<CommentDto[]>([]);

  const [newComment, setNewComment] = useState("");

  useEffect(() => {
    if (!itemId) return;
    api<CommentDto[]>(`/api/items/${itemId}/comments`)
      .then(setComments)
      .catch(() => {});
  }, [itemId]);

  const addComment = useCallback(async () => {
    if (!newComment.trim()) return;

    await api(`/api/items/${itemId}/comments`, {
      method: "POST",
      body: JSON.stringify({ body: newComment.trim() }),
    });

    setNewComment("");

    setComments(await api<CommentDto[]>(`/api/items/${itemId}/comments`));
  }, [itemId, newComment]);

  const removeComment = useCallback(async (commentId: string) => {
    await api(`/api/comments/${commentId}`, { method: "DELETE" });

    setComments((prev) => prev.filter((c) => c.commentId !== commentId));
  }, []);

  return { comments, newComment, setNewComment, addComment, removeComment };
}

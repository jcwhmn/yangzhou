"use client";

import { Box, Card, CardContent, Chip, Container, Stack, TextField, Typography } from "@mui/material";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";

type SearchResult = {
  itemId: string;
  projectKey: string;
  number: string;
  title: string;
  status: string;
};

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);

  useEffect(() => {
    if (!query.trim()) { setResults([]); return; }
    const timer = setTimeout(async () => {
      try {
        setResults(await api<SearchResult[]>(`/api/search?q=${encodeURIComponent(query.trim())}`));
      } catch { setResults([]); }
    }, 300);
    return () => clearTimeout(timer);
  }, [query]);

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h5" gutterBottom>
        搜索
      </Typography>
      <TextField
        autoFocus
        fullWidth
        placeholder="搜索 item 标题/描述…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        sx={{ mb: 2 }}
      />
      {results.length === 0 && query.trim() && (
        <Typography color="text.secondary">(无匹配结果)</Typography>
      )}
      <Stack spacing={1}>
        {results.map((r) => (
          <Link key={r.itemId} href={`/p/${r.projectKey}/i/${r.itemId}`} style={{ textDecoration: "none" }}>
            <Card>
              <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip size="small" label={r.number} variant="outlined" />
                  <Typography variant="body2">{r.title}</Typography>
                  <Chip size="small" label={r.status} variant="outlined" />
                </Stack>
              </CardContent>
            </Card>
          </Link>
        ))}
      </Stack>
    </Container>
  );
}

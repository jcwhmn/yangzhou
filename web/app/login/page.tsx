"use client";

import { Box, Button, Card, CardContent, Stack, TextField, Typography } from "@mui/material";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { bootstrapOrLogin, setToken } from "@/lib/api";
import { t } from "@/lib/texts";

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      setToken(await bootstrapOrLogin(username, password));
      router.replace("/");
    } catch (err) {
      setError(err instanceof Error ? err.message : t.login.failed);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Box sx={{ minHeight: "100vh", display: "grid", placeItems: "center", p: 2 }}>
      <Card sx={{ maxWidth: 380, width: "100%" }}>
        <CardContent>
          <Stack spacing={2} component="form" onSubmit={submit}>
            <Typography variant="h5" textAlign="center">
              {t.appName}
            </Typography>
            <TextField
              label={t.login.username}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoFocus
              required
            />
            <TextField
              label={t.login.password}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            {error && (
              <Typography color="error" variant="body2">
                {error}
              </Typography>
            )}
            <Button type="submit" variant="contained" disabled={busy}>
              {t.login.submit}
            </Button>
            <Typography variant="caption" color="text.secondary" textAlign="center">
              {t.login.hint}
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}

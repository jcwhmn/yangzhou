# YPJ-118(spec 0087):web 镜像(两阶段,next standalone)
# BACKEND_URL 是 build 期固化的(next.config.mjs 顶层读 env → 烤进 routes-manifest);
# compose 内 web→api 恒为 http://api:8080,三环境同值,故默认值写死、单镜像通吃
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG BACKEND_URL=http://api:8080
ENV BACKEND_URL=$BACKEND_URL
RUN npx next build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]

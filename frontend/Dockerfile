# syntax=docker/dockerfile:1.7

FROM node:22-alpine AS build

WORKDIR /workspace

COPY package.json package-lock.json ./

RUN --mount=type=cache,id=flowai-frontend-npm,target=/root/.npm \
    npm ci

COPY . .

RUN npm run build


FROM nginx:1.28-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build \
    /workspace/dist \
    /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s \
    --timeout=3s \
    --start-period=10s \
    --retries=5 \
    CMD wget -q --spider http://127.0.0.1/health || exit 1

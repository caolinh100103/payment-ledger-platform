# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
# Shown on the sign-in page of a public demo, e.g. "alice / correct horse battery staple".
ARG VITE_DEMO_CREDENTIALS
RUN npm run build

# Static files and the reverse proxy to the three services: one origin for the browser, so no CORS, and the refresh
# cookie stays same-site (ADR 0015).
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080

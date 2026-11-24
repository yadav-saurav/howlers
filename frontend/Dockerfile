# ---- build
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- serve: static files + reverse proxy for /api so the browser sees a single origin
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
# 127.0.0.1, not localhost: busybox wget resolves localhost to ::1 first, and nginx listens on IPv4 only.
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://127.0.0.1/healthz || exit 1

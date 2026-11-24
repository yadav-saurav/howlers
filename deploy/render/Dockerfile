# One-container image for free hosting (Render free web service, docs/FREE_HOSTING.md).
# nginx serves the built frontend and proxies /api and /ws to the Spring Boot API in the same container, so the
# browser sees a single origin exactly like the Docker Compose setup. Build context: the repository root.

# ---- frontend
FROM node:22-alpine AS web
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# ---- backend
FROM eclipse-temurin:21-jdk AS api
WORKDIR /src
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
# Strip CRLF line endings (a Windows checkout) so the wrapper script runs under Linux.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:resolve dependency:resolve-plugins
COPY backend/src src
RUN ./mvnw -q -B -DskipTests package

# ---- run
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends nginx openssl \
    && rm -rf /var/lib/apt/lists/* /etc/nginx/sites-enabled/default \
    && mkdir -p /app/data/uploads /usr/share/nginx/html
# Reuse the production nginx rules unchanged, except: the API is on 127.0.0.1 instead of the "backend" container
# (so no Docker DNS resolver), and the listen port comes from Render's PORT at start-up (start.sh).
COPY frontend/nginx.conf /etc/nginx/howlers.conf.template
RUN sed -i -e '/resolver 127.0.0.11/d' \
           -e 's#set $backend http://backend:8080;#set $backend http://127.0.0.1:8080;#' \
           -e 's#listen 80;#listen __PORT__;#' /etc/nginx/howlers.conf.template \
    && grep -q 'listen __PORT__;' /etc/nginx/howlers.conf.template \
    && grep -q 'http://127.0.0.1:8080' /etc/nginx/howlers.conf.template
COPY --from=web /src/dist /usr/share/nginx/html
COPY --from=api /src/target/howlers-backend.jar /app/app.jar
COPY deploy/render/start.sh /app/start.sh
RUN sed -i 's/\r$//' /app/start.sh && chmod +x /app/start.sh
WORKDIR /app
ENV STORAGE_LOCAL_DIR=/app/data/uploads
EXPOSE 10000
CMD ["/app/start.sh"]

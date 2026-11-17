# Portales web de SERVI-RED. Una imagen por portal:
#   docker build --build-arg PROYECTO=portal-publico .
#   docker build --build-arg PROYECTO=portal-operador .
ARG PROYECTO=portal-publico

FROM node:24.15.0-alpine3.22 AS construccion
ARG PROYECTO
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN NG_CLI_ANALYTICS=false npx ng build ${PROYECTO} --configuration production

FROM nginxinc/nginx-unprivileged:1.29.3-alpine
ARG PROYECTO
# El portal público pide la ubicación (denuncias); el de operación no la necesita.
ARG PERMISOS="camera=(), microphone=(), geolocation=()"
ENV PERMISOS=${PERMISOS} \
    GATEWAY_URL=http://gateway:8080 \
    IP_REAL_DESDE=127.0.0.1 \
    OPERACION_URL=http://portal-operador:8080 \
    NGINX_ENVSUBST_FILTER="^(PERMISOS|GATEWAY_URL|IP_REAL_DESDE|OPERACION_URL)$"
COPY nginx/portal.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/rutas-${PROYECTO}/ /etc/nginx/templates/rutas/
COPY --from=construccion /web/dist/${PROYECTO}/browser /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/salud || exit 1

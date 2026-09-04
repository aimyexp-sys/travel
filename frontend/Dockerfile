# --- build stage ---
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration production

# --- serve stage ---
FROM nginx:1.27-alpine
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
# The nginx:1.27-alpine image's own entrypoint runs envsubst over every file
# under /etc/nginx/templates/*.template (restricted to variables actually set
# in the container's env, so nginx's own $host/$remote_addr/$http_upgrade are
# left untouched) and writes the result to /etc/nginx/conf.d/ before nginx
# starts - no custom entrypoint script needed.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# Local docker-compose defaults: plain HTTP to the Compose service name/port,
# no domain suffix. Render overrides all three (see render.yaml) to route
# over the backend's public onrender.com URL instead of the private network -
# see nginx.conf.template for why.
ENV PORT=80
ENV BACKEND_SCHEME=http
ENV BACKEND_HOST=backend:8080
ENV BACKEND_DOMAIN_SUFFIX=""
# Turns on the nginx image's built-in 15-local-resolvers.envsh entrypoint
# script, which exports NGINX_LOCAL_RESOLVERS from the container's own
# /etc/resolv.conf - used by the resolver directive in nginx.conf.template
# so proxy_pass resolves the backend hostname at request time, not once at
# nginx startup.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
EXPOSE 80

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
# Local docker-compose overrides both of these (PORT=80, BACKEND_HOSTPORT=backend:8080
# is already the Compose service name/port, so it doesn't even need overriding there).
# Render sets its own PORT and BACKEND_HOSTPORT (see render.yaml); these are just
# safe defaults for running the image standalone.
ENV PORT=80
ENV BACKEND_HOSTPORT=backend:8080
# Turns on the nginx image's built-in 15-local-resolvers.envsh entrypoint
# script, which exports NGINX_LOCAL_RESOLVERS from the container's own
# /etc/resolv.conf - used by the resolver directive in nginx.conf.template
# so proxy_pass resolves the backend hostname at request time, not once at
# nginx startup (see nginx.conf.template for why that matters on Render).
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
EXPOSE 80

# Next.js image, shared by the storefront and the admin dashboard.
#
# Which app is built is chosen with `--build-arg APP_NAME=storefront|admin`. One Dockerfile rather
# than two near-identical ones: the build is genuinely the same, and two copies drift.

ARG APP_NAME=storefront

# ------------------------------------------------------------------ dependencies --
FROM node:22-alpine AS deps

WORKDIR /app

# Manifests only, so the install layer is cached on the lockfile rather than on application source.
COPY package.json package-lock.json ./
COPY packages/api-client/package.json ./packages/api-client/
COPY apps/storefront/package.json ./apps/storefront/
COPY apps/admin/package.json ./apps/admin/

# `npm ci` installs exactly the lockfile - a build that resolves a different version than the one
# that was tested is not a reproducible build.
RUN npm ci --no-audit --no-fund

# ------------------------------------------------------------------------ build --
FROM node:22-alpine AS build

ARG APP_NAME
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_SITE_URL
ARG NEXT_PUBLIC_CDN_BASE_URL

WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* values are inlined into the client bundle at build time and cannot be changed
# afterwards, so they have to be present here rather than injected at runtime. Anything that must
# vary per environment without a rebuild is a server-side variable instead.
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_SITE_URL=${NEXT_PUBLIC_SITE_URL}
ENV NEXT_PUBLIC_CDN_BASE_URL=${NEXT_PUBLIC_CDN_BASE_URL}
ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build --workspace=@eshop/${APP_NAME}

# ---------------------------------------------------------------------- runtime --
FROM node:22-alpine AS runtime

ARG APP_NAME
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 --ingroup nodejs nextjs

# `output: 'standalone'` traces the actual import graph and emits a self-contained server with only
# the modules it reaches - roughly 150 MB instead of the full node_modules tree.
COPY --from=build --chown=nextjs:nodejs /app/apps/${APP_NAME}/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/apps/${APP_NAME}/.next/static ./apps/${APP_NAME}/.next/static
COPY --from=build --chown=nextjs:nodejs /app/apps/${APP_NAME}/public ./apps/${APP_NAME}/public

# Resolved at build time so the runtime stage needs no shell expansion in its entrypoint.
ENV APP_ENTRYPOINT=apps/${APP_NAME}/server.js

USER nextjs

EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O- "http://localhost:${PORT}/api/health" || exit 1

CMD ["sh", "-c", "node ${APP_ENTRYPOINT}"]

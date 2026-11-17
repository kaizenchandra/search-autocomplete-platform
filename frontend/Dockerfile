FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG API_ORIGIN=http://api:8080
ENV API_ORIGIN=$API_ORIGIN
RUN npm run build
FROM node:22-alpine
WORKDIR /app
ENV HOSTNAME=0.0.0.0
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
USER node
CMD ["node","server.js"]

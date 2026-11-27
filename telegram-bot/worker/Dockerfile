FROM node:24.15.0-slim
WORKDIR /bot
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY . ./worker
USER node
CMD ["node", "worker/main.mjs"]

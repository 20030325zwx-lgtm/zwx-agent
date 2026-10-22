FROM mcr.microsoft.com/playwright:v1.64.1-noble

WORKDIR /app
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
RUN mkdir -p /app/screenshots

EXPOSE 8129
CMD ["node", "src/server.js"]

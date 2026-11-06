FROM mcr.microsoft.com/playwright:v1.64.1-noble
USER root
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY *.mjs ./
USER pwuser
ENV NODE_ENV=production
EXPOSE 3000
CMD ["node", "server.mjs"]

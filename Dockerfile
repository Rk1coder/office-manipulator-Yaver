FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn -q -DskipTests package

# The same lockfile is used in local development and production.
FROM node:24.19.0-alpine AS node-runtime
WORKDIR /modules
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN apk add --no-cache bubblewrap util-linux font-dejavu libstdc++
COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /modules/node_modules ./node_modules

ENV TURKISH_FONT_REGULAR=/usr/share/fonts/dejavu/DejaVuSans.ttf
ENV TURKISH_FONT_BOLD=/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
# Trusted build-time smoke test: catches missing fonts/fontkit in the runtime image.
RUN node -e 'const fs=require("fs"); const {PDFDocument}=require("pdf-lib"); (async()=>{const d=await PDFDocument.create(); d.registerFontkit(require("@pdf-lib/fontkit")); const f=await d.embedFont(fs.readFileSync(process.env.TURKISH_FONT_REGULAR)); d.addPage().drawText("Türkçe: ğüşıöçĞÜŞİÖÇ",{font:f}); const b=await d.save(); if((await PDFDocument.load(b)).getPageCount()!==1) process.exit(1); console.log("Unicode PDF runtime smoke test passed");})().catch(e=>{console.error(e);process.exit(1)})'

FROM runtime
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /data/files && chown -R appuser:appgroup /data/files
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

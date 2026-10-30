
FROM nginx:stable-alpine

LABEL org.opencontainers.image.source="https://github.com/thomcgn/NoizyCologne"

# Statische Landingpage bereitstellen
COPY index.html /usr/share/nginx/html/index.html
COPY styles.css /usr/share/nginx/html/styles.css
COPY assets/ /usr/share/nginx/html/assets/

EXPOSE 80

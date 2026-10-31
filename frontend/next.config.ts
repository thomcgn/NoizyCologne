import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    // Nur der lokale Next-Dev-Server darf die lokale Spring-Boot-API proxien.
    // Für Produktions-Builds wird bewusst keine öffentliche API-Route erzeugt.
    if (process.env.NODE_ENV !== "development") {
      return [];
    }

    const backendOrigin = process.env.LOCAL_BACKEND_URL ?? "http://127.0.0.1:8080";
    return [
      {
        source: "/api/v1/:path*",
        destination: `${backendOrigin}/api/v1/:path*`,
      },
    ];
  },
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;

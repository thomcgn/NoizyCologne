export const themes = ["graphite", "midnight", "light"] as const;
export type Theme = typeof themes[number];
export const densities = ["compact", "comfortable"] as const;
export type Density = typeof densities[number];
export const defaultTheme: Theme = "graphite";
export const defaultDensity: Density = "compact";

// Only validated literals reach the document. Runs before body content is painted.
export const preferenceBootstrap = `(()=>{let t=${JSON.stringify(defaultTheme)},d=${JSON.stringify(defaultDensity)};try{let v=localStorage.getItem("noizy-theme");if(v==="dark")v="midnight";if(${JSON.stringify(themes)}.includes(v))t=v;v=localStorage.getItem("noizy-density");if(${JSON.stringify(densities)}.includes(v))d=v;}catch{}document.documentElement.dataset.theme=t;document.documentElement.dataset.density=d;})();`;

import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        background: "var(--background)",
        foreground: "var(--foreground)",
        muted: "var(--muted)",
        panel: "var(--panel)",
        "panel-2": "var(--panel-2)",
        border: "var(--panel-border)",
        "border-strong": "var(--panel-border-strong)",
        accent: "var(--accent)",
        "accent-foreground": "var(--accent-foreground)",
        success: "var(--success)",
        warning: "var(--warning)",
        danger: "var(--danger)",
        "danger-foreground": "var(--danger-foreground)",
      },
    },
  },
  plugins: [],
};
export default config;

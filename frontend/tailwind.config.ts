import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        aura: {
          bg: "#0b0d12",
          surface: "#11141b",
          surface2: "#161a24",
          border: "#232936",
          text: "#e6e8ee",
          mute: "#8b93a7",
          accent: "#7c9dff",
          accent2: "#52e0c4",
          warn: "#f5b467",
          err: "#ef6b7a",
        },
      },
      fontFamily: {
        sans: ['"Inter"', "system-ui", "-apple-system", "sans-serif"],
        mono: ['"JetBrains Mono"', "ui-monospace", "monospace"],
      },
      animation: {
        pulseSoft: "pulseSoft 1.6s ease-in-out infinite",
      },
      keyframes: {
        pulseSoft: {
          "0%,100%": { opacity: "1" },
          "50%": { opacity: "0.55" },
        },
      },
    },
  },
  plugins: [],
};
export default config;

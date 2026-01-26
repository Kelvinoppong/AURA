import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AURA — Autonomous User Response Agent",
  description:
    "Multi-agent AI support assistant. RAG + hybrid LLM routing + real-time streaming.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="bg-aura-bg text-aura-text antialiased">{children}</body>
    </html>
  );
}
